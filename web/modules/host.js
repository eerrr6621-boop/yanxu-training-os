// Internal synthetic-data debug host; never imports the production SPA.
// Actual stylesheet filenames and ownership were checked against each module entry.
export const MODULES = Object.freeze([
  ['M01', 'identity', '账号权限', '组织、成员与权限范围', 'styles.css', 'host'],
  ['M02', 'intake', '需求投标', '需求登记、投标与业务流转', 'intake.css', 'host'],
  ['M03', 'approvals', '审批待办', '审批流程、待办与处理记录', 'style.css', 'module'],
  ['M04', 'catalog', '课程师资', '课程资源、讲师与资格管理', 'style.css', 'module'],
  ['M05', 'settlement', '交付课酬', '授课交付、计酬与结算明细', 'styles.css', 'module'],
  ['M06', 'reports', '统计导出', '业务统计、明细核对与导出', 'styles.css', 'module'],
  ['M07', 'survey-results', '问卷汇总', '已汇总问卷结果与反馈', 'styles.css', 'host'],
  ['M08', 'summaries', '内部总结', '内部报告、复盘与总结', 'styles.css', 'module'],
  ['S01', 'notifications', '消息渠道', '消息模板、渠道与演示记录', 'style.css', 'host']
].map(([id, slug, title, description, stylesheet, styleOwner]) => Object.freeze({ id, slug, title, description, stylesheet, styleOwner })));

export const DEMO_USER = Object.freeze({
  id: 'synthetic-demo-user', name: '合成演示用户', displayName: '合成演示用户',
  user_code: 'DEMO-FILLER-01', display_name: '合成演示用户',
  role: 'demo', roles: Object.freeze(['demo']), synthetic: true
});

export function findModule(id) {
  const key = String(id || '').toLowerCase();
  return MODULES.find(module => module.id.toLowerCase() === key || module.slug === key);
}

export function parseRoute(hash = '') {
  const raw = String(hash).replace(/^#\/?/, '');
  const split = raw.indexOf('?');
  const name = split < 0 ? raw : raw.slice(0, split);
  let id;
  try { id = decodeURIComponent(name || 'M01'); } catch { id = ''; }
  return { module: findModule(id), params: Object.freeze(Object.fromEntries(new URLSearchParams(split < 0 ? '' : raw.slice(split + 1)))) };
}

export function routeHash(moduleId, params = {}) {
  const module = findModule(moduleId);
  if (!module) throw new Error('未找到该演示模块');
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null) search.set(key, String(value));
  }
  return `#${module.id}${search.size ? `?${search}` : ''}`;
}

export async function rejectDemoRequest() {
  const error = new Error('合成数据演示禁止业务网络请求');
  error.code = 'DEMO_NETWORK_BLOCKED';
  throw error;
}

const defaultLoader = module => import(`./${module.slug}/index.js`);

export function createDemoHost({ document: doc, window: win, loadModule = defaultLoader } = {}) {
  if (!doc || !win) throw new TypeError('演示宿主需要页面环境');
  const get = id => {
    const element = doc.getElementById(id);
    if (!element) throw new Error(`演示页面缺少 ${id}`);
    return element;
  };
  const outlet = get('module-outlet'), heading = get('module-heading');
  const code = get('module-code'), description = get('module-description');
  const status = get('module-status'), nav = get('module-nav');
  const menu = get('module-menu-toggle'), toast = get('module-notification'), skip = get('module-skip');
  let active = null, disposed = false, toastTimer = null;
  const links = new Map();
  const activeNow = record => !disposed && active === record && !record.controller.signal.aborted;

  function setStatus(label, state) { status.textContent = label; status.dataset.state = state; }
  function notice(root, title, detail) {
    const box = doc.createElement('div'), h2 = doc.createElement('h2'), p = doc.createElement('p');
    box.className = 'yx-host-notice'; h2.textContent = title; p.textContent = detail;
    box.append(h2, p); root.replaceChildren(box);
  }
  function notify(message) {
    if (disposed) return false;
    toast.textContent = String(message ?? '').slice(0, 500); toast.hidden = false;
    win.clearTimeout(toastTimer);
    toastTimer = win.setTimeout(() => { toast.hidden = true; }, 5000);
    return true;
  }
  function cleanup(record) {
    if (!record || typeof record.cleanup !== 'function') return;
    const release = record.cleanup; record.cleanup = null;
    try { Promise.resolve(release()).catch(() => {}); } catch { /* Route switching must remain usable. */ }
  }
  function release(record) {
    if (!record) return;
    record.controller.abort(); record.root.remove();
    for (const link of record.styles) link.remove();
    cleanup(record);
  }
  function closeMenu() {
    nav.dataset.open = 'false'; menu.setAttribute('aria-expanded', 'false'); menu.textContent = '展开导航';
  }
  function toggleMenu() {
    const open = nav.dataset.open !== 'true';
    nav.dataset.open = String(open); menu.setAttribute('aria-expanded', String(open));
    menu.textContent = open ? '收起导航' : '展开导航';
  }
  function skipToContent(event) {
    event.preventDefault();
    heading.focus();
  }
  function navigate(moduleId, params) {
    if (disposed) return false;
    const next = routeHash(moduleId, params);
    if (win.location.hash !== next) win.location.hash = next;
    return true;
  }
  // An explicit styles export overrides the checked-in resource mapping.
  // Only host-owned defaults are added here; the other entries load their own CSS.
  // Every host-owned link leaves with the route, including unfinished loads.
  async function attachStyles(record, module, namespace) {
    const paths = namespace.styles == null
      ? (module.styleOwner === 'host' ? [module.stylesheet] : [])
      : (Array.isArray(namespace.styles) ? namespace.styles : [namespace.styles]);
    const directory = new URL(`./${module.slug}/`, new URL('./', win.location.href));
    await Promise.all(paths.map(path => {
      if (typeof path !== 'string') throw new TypeError('模块样式路径无效');
      const url = new URL(path, directory);
      if (url.origin !== directory.origin || !url.pathname.startsWith(directory.pathname) || !url.pathname.endsWith('.css')) {
        throw new Error('演示样式必须位于当前模块目录');
      }
      return new Promise((resolve, reject) => {
        const link = doc.createElement('link');
        link.rel = 'stylesheet'; link.href = url.href; record.styles.push(link);
        const finish = error => {
          link.onload = null; link.onerror = null;
          record.controller.signal.removeEventListener('abort', aborted);
          error ? reject(error) : resolve();
        };
        const aborted = () => finish(new Error('模块已切换'));
        link.onload = () => finish(); link.onerror = () => finish(new Error('模块样式未能加载'));
        record.controller.signal.addEventListener('abort', aborted, { once: true });
        if (record.controller.signal.aborted) aborted(); else doc.head.append(link);
      });
    }));
  }

  async function render() {
    if (disposed) return;
    release(active); active = null; closeMenu();
    win.clearTimeout(toastTimer); toast.hidden = true;
    const route = parseRoute(win.location.hash);
    for (const [id, link] of links) {
      if (id === route.module?.id) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
    }
    const root = doc.createElement('section'); root.className = 'yx-host-module-root';
    outlet.replaceChildren(root);
    if (!route.module) {
      heading.textContent = '未找到模块'; code.textContent = '模块演示'; description.textContent = '请从导航选择一个演示模块。';
      setStatus('地址无效', 'error'); outlet.setAttribute('aria-busy', 'false');
      notice(root, '未找到模块', '这个演示地址无效，请从模块导航重新选择。');
      doc.title = '研序 · 模块演示'; return;
    }
    const module = route.module;
    const record = { root, controller: new AbortController(), cleanup: null, styles: [] };
    active = record; // Modules create their own yx-<slug> root; do not apply its padding twice.
    heading.textContent = module.title; code.textContent = `${module.id} / 模块演示`;
    description.textContent = module.description; doc.title = `${module.title} · 研序模块演示`;
    setStatus('加载中', 'loading'); outlet.setAttribute('aria-busy', 'true');
    notice(root, '正在加载演示', '当前页面仅使用合成数据。');
    let phase = 'import';
    try {
      const namespace = await loadModule(module);
      if (!activeNow(record)) return;
      if (typeof namespace.mount !== 'function') throw new Error('模块尚未提供演示入口');
      phase = 'mount';
      await attachStyles(record, module, namespace);
      if (!activeNow(record)) return;
      root.replaceChildren();
      const context = Object.freeze({
        mode: 'demo', signal: record.controller.signal, user: DEMO_USER,
        request: rejectDemoRequest, params: route.params,
        notify: message => activeNow(record) ? notify(message) : false,
        navigate: (moduleId, params) => activeNow(record) ? navigate(moduleId, params) : false
      });
      const result = await namespace.mount(root, context);
      if (typeof result === 'function') record.cleanup = result;
      if (!activeNow(record)) { cleanup(record); return; }
      setStatus('演示 · 待验收', 'demo');
    } catch (error) {
      if (!activeNow(record)) return;
      // Detach failed mounts too, so delayed work cannot overwrite the error panel.
      record.controller.abort(); root.remove();
      for (const link of record.styles) link.remove();
      cleanup(record);
      const fallback = doc.createElement('section'); outlet.replaceChildren(fallback);
      if (phase === 'import') {
        setStatus('制作中', 'pending');
        notice(fallback, '模块制作中', '当前模块尚未提供可用的演示入口。请先查看其他模块；这里不表示已通过验收。');
      } else {
        setStatus('演示加载失败', 'error');
        notice(fallback, '演示暂时无法显示', '模块初始化或样式加载失败。请切换模块后重试；当前未进行业务网络操作。');
      }
    } finally {
      if (!disposed && active === record) outlet.setAttribute('aria-busy', 'false');
    }
  }

  for (const module of MODULES) {
    const link = doc.createElement('a'), label = doc.createElement('span'), name = doc.createElement('span');
    link.href = routeHash(module.id); label.className = 'yx-host-nav-code'; label.textContent = module.id;
    name.textContent = module.title; link.append(label, name); nav.append(link); links.set(module.id, link);
  }
  menu.addEventListener('click', toggleMenu);
  skip.addEventListener('click', skipToContent);
  const onHashChange = () => { void render(); };
  win.addEventListener('hashchange', onHashChange);
  const ready = render();
  return Object.freeze({
    ready, navigate, render,
    dispose() {
      if (disposed) return;
      disposed = true; release(active); active = null;
      win.clearTimeout(toastTimer); toast.hidden = true;
      win.removeEventListener('hashchange', onHashChange); menu.removeEventListener('click', toggleMenu);
      skip.removeEventListener('click', skipToContent);
      outlet.replaceChildren(); nav.replaceChildren();
    }
  });
}

if (typeof document !== 'undefined' && typeof window !== 'undefined' && document.getElementById('module-outlet')) {
  const host = createDemoHost({ document, window });
  window.addEventListener('pagehide', event => { if (!event.persisted) host.dispose(); });
}
