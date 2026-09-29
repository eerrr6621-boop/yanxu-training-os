#!/usr/bin/env node
// Pure Node lifecycle tests with a minimal DOM and real local CSS resource checks.
// No network or production data.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'web/modules/host.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'web/modules/index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'web/modules/host.css'), 'utf8');

class Element extends EventTarget {
  constructor(tag) {
    super(); this.tagName = tag; this.children = []; this.dataset = {}; this.attributes = {};
    this.parentNode = null; this.className = ''; this.hidden = false; this.value = '';
    this.classList = { add: name => { this.className += ` ${name}`; } };
  }
  set textContent(text) { this.replaceChildren(); this.value = String(text); }
  get textContent() { return this.value + this.children.map(child => child.textContent).join(''); }
  append(...children) {
    for (const child of children) {
      child.remove(); child.parentNode = this; this.children.push(child);
      if (this.tagName === 'head' && child.tagName === 'link' && !this.delayStyles) {
        queueMicrotask(() => {
          const url = new URL(child.href);
          const resource = path.join(root, 'web', decodeURIComponent(url.pathname));
          const exists = url.origin === 'http://localhost:63880' && fs.existsSync(resource) && fs.statSync(resource).isFile();
          if (exists) child.onload?.(); else child.onerror?.();
        });
      }
    }
  }
  replaceChildren(...children) {
    for (const child of this.children) child.parentNode = null;
    this.children = []; this.value = ''; this.append(...children);
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  removeAttribute(name) { delete this.attributes[name]; }
  getAttribute(name) { return this.attributes[name] ?? null; }
  focus() { this.focused = true; }
}
function environment(hash = '') {
  const ids = ['module-outlet', 'module-heading', 'module-code', 'module-description', 'module-status', 'module-nav', 'module-menu-toggle', 'module-notification', 'module-skip'];
  const elements = Object.fromEntries(ids.map(id => [id, new Element('div')]));
  const document = { head: new Element('head'), title: '', getElementById: id => elements[id], createElement: tag => new Element(tag) };
  const window = new EventTarget();
  let currentHash = hash;
  window.location = {
    get href() { return `http://localhost:63880/modules/${currentHash}`; },
    get hash() { return currentHash; },
    set hash(value) { if (value !== currentHash) { currentHash = value; queueMicrotask(() => window.dispatchEvent(new Event('hashchange'))); } }
  };
  window.setTimeout = (callback, ms) => { const timer = setTimeout(callback, ms); timer.unref(); return timer; };
  window.clearTimeout = clearTimeout;
  return { document, window, elements };
}
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const flush = () => new Promise(resolve => setImmediate(resolve));
let count = 0;
async function test(name, run) { await run(); count++; console.log(`PASS ${name}`); }

(async () => {
  const api = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const { MODULES, DEMO_USER, parseRoute, routeHash, rejectDemoRequest, createDemoHost } = api;
  const setup = (loader, hash) => { const env = environment(hash); return { ...env, host: createDemoHost({ ...env, loadModule: loader }) }; };
  await test('fixed nine-module order, aliases and synthetic identity', () => {
    assert.equal(MODULES.map(m => m.id).join(','), 'M01,M02,M03,M04,M05,M06,M07,M08,S01');
    for (const module of MODULES) {
      assert.equal(parseRoute(`#${module.id}`).module, module);
      assert.equal(parseRoute(`#/${module.slug}`).module, module);
    }
    assert.equal(DEMO_USER.synthetic, true); assert.match(DEMO_USER.name, /合成/);
    assert.equal(DEMO_USER.user_code, 'DEMO-FILLER-01'); assert.equal(DEMO_USER.display_name, '合成演示用户');
    assert.equal(DEMO_USER.role, 'demo');
    assert(Object.isFrozen(DEMO_USER)); assert(Object.isFrozen(DEMO_USER.roles));
  });
  await test('all nine stylesheet resources exist and module-owned references match', () => {
    const owners = [];
    for (const module of MODULES) {
      const directory = path.join(root, 'web/modules', module.slug);
      const stylesheets = fs.readdirSync(directory).filter(file => file.endsWith('.css'));
      assert.deepEqual(stylesheets, [module.stylesheet], `${module.id}: actual CSS filename must match registry`);
      const styleSource = fs.readFileSync(path.join(directory, module.stylesheet), 'utf8');
      assert(styleSource.includes(`.yx-${module.slug}`), `${module.id}: scoped CSS must be nonempty`);
      if (module.styleOwner === 'module') {
        const entry = fs.readFileSync(path.join(directory, 'index.js'), 'utf8');
        assert(entry.includes(`./${module.stylesheet}`), `${module.id}: module must reference the mapped CSS`);
        assert(entry.includes('stylesheet'), `${module.id}: module must create a stylesheet link`);
      } else owners.push(module.id);
    }
    assert.deepEqual(owners, ['M01', 'M02', 'M07', 'S01']);
    assert.equal(MODULES.find(module => module.id === 'M02').stylesheet, 'intake.css');
  });
  await test('host-owned module styles load from real filenames and leave on route changes', async () => {
    const env = setup(async () => ({ mount: async () => {} }));
    await env.host.ready;
    for (const module of MODULES) {
      env.host.navigate(module.id); await flush();
      const styleLinks = env.document.head.children;
      assert.equal(styleLinks.length, module.styleOwner === 'host' ? 1 : 0, `${module.id}: no duplicate self-managed stylesheet`);
      if (module.styleOwner === 'host') assert.equal(styleLinks[0].href, `http://localhost:63880/modules/${module.slug}/${module.stylesheet}`);
      assert.equal(env.elements['module-status'].textContent, '演示 · 待验收', `${module.id}: stylesheet must load successfully`);
      assert.equal(env.elements['module-outlet'].children[0].className, 'yx-host-module-root', `${module.id}: no duplicated module scope on host wrapper`);
    }
    env.host.dispose(); assert.equal(env.document.head.children.length, 0);
  });
  await test('M02 waits for intake.css to load before mounting without any styles export', async () => {
    let mounts = 0;
    const env = setup(async () => ({ mount: async () => { mounts++; } }), '#M02');
    env.document.head.delayStyles = true; await flush();
    assert.equal(mounts, 0);
    const link = env.document.head.children[0];
    assert.equal(link.href, 'http://localhost:63880/modules/intake/intake.css');
    link.onload(); await env.host.ready;
    assert.equal(mounts, 1); env.host.dispose();
  });
  await test('missing stylesheet resource is a visible failure and never mounts', async () => {
    let mounts = 0;
    const env = setup(async () => ({ styles: ['./missing.css'], mount: async () => { mounts++; } }), '#M02');
    await env.host.ready;
    assert.equal(mounts, 0); assert.equal(env.elements['module-status'].textContent, '演示加载失败');
    assert.equal(env.document.head.children.length, 0); env.host.dispose();
  });
  await test('hash deep links, safe query values and rejected unknown routes', () => {
    assert.equal(parseRoute('').module.id, 'M01');
    const parsed = parseRoute(routeHash('survey-results', { title: '合成 & 反馈', row: 2, empty: null }));
    assert.equal(parsed.module.id, 'M07'); assert.deepEqual(parsed.params, { title: '合成 & 反馈', row: '2' });
    assert(Object.isFrozen(parsed.params));
    for (const hash of ['#../app', '#https://invalid.example/', '#%E0%A4%A', '#__proto__']) assert.equal(parseRoute(hash).module, undefined);
    assert.throws(() => routeHash('../../app'));
    assert.equal({}.polluted, undefined);
  });
  await test('every demo request rejects before any fetch, for every method', async () => {
    let calls = 0; const original = globalThis.fetch;
    globalThis.fetch = async () => { calls++; throw new Error('network must not run'); };
    try {
      for (const url of ['/api/session', 'https://production.invalid/api', '//other.invalid/api', undefined]) {
        for (const method of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']) {
          await assert.rejects(rejectDemoRequest(url, { method }), { code: 'DEMO_NETWORK_BLOCKED' });
        }
      }
      assert.equal(calls, 0);
    } finally { globalThis.fetch = original; }
  });
  await test('mount receives demo contract even when route requests live', async () => {
    let context;
    const env = setup(async () => ({ mount: async (root, ctx) => { context = ctx; root.textContent = '合成模块'; } }), '#M07?mode=live&row=demo-2');
    await env.host.ready;
    assert.equal(context.mode, 'demo'); assert.equal(context.params.mode, 'live'); assert.equal(context.params.row, 'demo-2');
    assert.equal(context.user, DEMO_USER); assert.equal(context.signal.aborted, false); assert(Object.isFrozen(context));
    for (const method of ['notify', 'navigate', 'request']) assert.equal(typeof context[method], 'function');
    assert.equal(env.elements['module-nav'].children.length, 9);
    assert.equal(env.elements['module-nav'].children[6].getAttribute('aria-current'), 'page');
    assert.match(env.elements['module-status'].textContent, /待验收/);
    assert.equal(env.elements['module-outlet'].getAttribute('aria-busy'), 'false');
    env.host.dispose(); assert.equal(context.signal.aborted, true);
  });
  await test('context navigation, hashchange and params reach the new module', async () => {
    const visits = [];
    const env = setup(async module => ({ mount: async (root, ctx) => { visits.push([module.id, ctx]); root.textContent = module.id; } }));
    await env.host.ready;
    visits[0][1].navigate('settlement', { record: 'synthetic-5' }); await flush();
    assert.equal(env.window.location.hash, '#M05?record=synthetic-5');
    assert.equal(visits.at(-1)[0], 'M05'); assert.equal(visits.at(-1)[1].params.record, 'synthetic-5');
    assert.equal(visits[0][1].signal.aborted, true);
    env.window.location.hash = '#M01'; await flush();
    assert.equal(visits.at(-1)[0], 'M01');
    env.host.dispose();
  });
  await test('phone navigation toggles and closes after route selection', async () => {
    const env = setup(async () => ({ mount: async () => {} })); await env.host.ready;
    const menu = env.elements['module-menu-toggle'];
    menu.dispatchEvent(new Event('click'));
    assert.equal(menu.getAttribute('aria-expanded'), 'true'); assert.equal(env.elements['module-nav'].dataset.open, 'true');
    env.host.navigate('M02'); await flush(); assert.equal(menu.getAttribute('aria-expanded'), 'false');
    assert.match(css, /@media \(max-width: 800px\)/);
    env.host.dispose();
  });
  await test('accessible skip link focuses content without altering hash route', async () => {
    const env = setup(async () => ({ mount: async () => {} }), '#M04?course=demo'); await env.host.ready;
    const event = new Event('click', { cancelable: true });
    env.elements['module-skip'].dispatchEvent(event);
    assert.equal(event.defaultPrevented, true); assert.equal(env.elements['module-heading'].focused, true);
    assert.equal(env.window.location.hash, '#M04?course=demo');
    assert.equal(env.elements['module-heading'].textContent, '课程师资'); env.host.dispose();
  });
  await test('missing entry is explicitly in progress; unknown route never imports', async () => {
    let imports = 0;
    const env = setup(async () => { imports++; throw new Error('missing entry'); }); await env.host.ready;
    assert.equal(env.elements['module-status'].textContent, '制作中');
    assert.match(env.elements['module-outlet'].textContent, /不表示已通过验收/);
    env.host.navigate('M02'); await flush(); assert.equal(imports, 2);
    env.window.location.hash = '#missing'; await flush();
    assert.equal(imports, 2); assert.equal(env.elements['module-status'].textContent, '地址无效');
    env.host.dispose();
  });
  await test('entry without mount is in progress', async () => {
    const env = setup(async () => ({})); await env.host.ready;
    assert.equal(env.elements['module-status'].textContent, '制作中'); env.host.dispose();
  });
  await test('late import never mounts after navigation', async () => {
    const gate = deferred(); let staleMounts = 0;
    const env = setup(module => module.id === 'M01' ? gate.promise : Promise.resolve({ mount: async root => { root.textContent = 'new page'; } }));
    env.host.navigate('M02'); await flush();
    gate.resolve({ mount: async () => { staleMounts++; } }); await env.host.ready;
    assert.equal(staleMounts, 0); assert.equal(env.elements['module-outlet'].textContent, 'new page');
    assert.equal(env.elements['module-heading'].textContent, '需求投标'); env.host.dispose();
  });
  await test('late mount only changes detached root; its cleanup runs once', async () => {
    const gate = deferred(); let oldRoot, oldContext, cleanups = 0;
    const env = setup(async module => ({ mount: async (root, ctx) => {
      if (module.id === 'M01') { oldRoot = root; oldContext = ctx; await gate.promise; root.textContent = 'late old output'; return () => { cleanups++; }; }
      root.textContent = 'current module';
    } }));
    await flush(); env.host.navigate('M02'); await flush();
    assert.equal(oldContext.signal.aborted, true); assert.equal(oldRoot.parentNode, null);
    assert.equal(oldContext.notify('stale toast'), false); assert.equal(oldContext.navigate('M08'), false);
    gate.resolve(); await env.host.ready;
    assert.equal(cleanups, 1); assert.equal(env.elements['module-outlet'].textContent, 'current module');
    assert.equal(env.window.location.hash, '#M02'); env.host.dispose(); assert.equal(cleanups, 1);
  });
  await test('normal cleanup runs exactly once after signal abort, even if it throws', async () => {
    let cleanups = 0; let wasAborted = false;
    const env = setup(async () => ({ mount: async (root, ctx) => () => { cleanups++; wasAborted = ctx.signal.aborted; throw new Error('cleanup failure'); } }));
    await env.host.ready; env.host.navigate('M02'); await flush();
    assert.equal(cleanups, 1); assert.equal(wasAborted, true);
    assert.equal(env.elements['module-heading'].textContent, '需求投标');
    env.host.dispose(); env.host.dispose(); assert.equal(cleanups, 2);
  });
  await test('failed mount is distinguished from missing entry and cannot overwrite fallback', async () => {
    let failedRoot, failedContext;
    const env = setup(async () => ({ mount: async (root, ctx) => { failedRoot = root; failedContext = ctx; throw new Error('broken module'); } }));
    await env.host.ready;
    assert.equal(env.elements['module-status'].textContent, '演示加载失败');
    assert.equal(failedContext.signal.aborted, true); assert.equal(failedRoot.parentNode, null);
    failedRoot.textContent = 'late bad write'; assert.match(env.elements['module-outlet'].textContent, /演示暂时无法显示/);
    env.host.dispose();
  });
  await test('optional module CSS loads locally and is removed on navigation', async () => {
    const env = setup(async module => ({ styles: module.id === 'M01' ? ['./styles.css'] : [], mount: async root => { root.textContent = 'styled'; } }));
    await env.host.ready;
    assert.equal(env.document.head.children[0].href, 'http://localhost:63880/modules/identity/styles.css');
    env.host.navigate('M02'); await flush(); assert.equal(env.document.head.children.length, 0); env.host.dispose();
  });
  await test('pending CSS is cancelled on navigation and never starts stale mount', async () => {
    const mounts = [];
    const env = setup(async module => ({ styles: module.id === 'M01' ? ['./styles.css'] : [], mount: async () => { mounts.push(module.id); } }));
    env.document.head.delayStyles = true; await flush();
    assert.equal(env.document.head.children.length, 1);
    env.host.navigate('M02'); await flush(); await env.host.ready;
    assert.deepEqual(mounts, ['M02']); assert.equal(env.document.head.children.length, 0);
    assert.equal(env.elements['module-heading'].textContent, '需求投标'); env.host.dispose();
  });
  await test('CSS outside module directory is rejected before mount', async () => {
    for (const style of ['https://external.invalid/style.css', '../host.css', './entry.js']) {
      let mounts = 0;
      const env = setup(async () => ({ styles: [style], mount: async () => { mounts++; } })); await env.host.ready;
      assert.equal(mounts, 0); assert.equal(env.elements['module-status'].textContent, '演示加载失败');
      assert.equal(env.document.head.children.length, 0); env.host.dispose();
    }
  });
  await test('notifications use text and clear when switching', async () => {
    let ctx;
    const env = setup(async () => ({ mount: async (root, context) => { ctx = context; } })); await env.host.ready;
    ctx.notify('<img src=x onerror=alert(1)>');
    assert.equal(env.elements['module-notification'].textContent, '<img src=x onerror=alert(1)>');
    assert.equal(env.elements['module-notification'].children.length, 0);
    env.host.navigate('M02'); await flush(); assert.equal(env.elements['module-notification'].hidden, true); env.host.dispose();
  });
  await test('dispose during import prevents later content or listeners', async () => {
    const gate = deferred(); let mounts = 0;
    const env = setup(() => gate.promise); env.host.dispose();
    gate.resolve({ mount: async () => { mounts++; } }); await env.host.ready;
    env.window.location.hash = '#M02'; await flush();
    assert.equal(mounts, 0); assert.equal(env.elements['module-nav'].children.length, 0);
    assert.equal(env.elements['module-outlet'].children.length, 0); assert.equal(env.host.navigate('M03'), false);
  });
  await test('dispose during mount still runs cleanup returned after disposal', async () => {
    const gate = deferred(); let ctx, lateRoot, releases = 0;
    const env = setup(async () => ({ mount: async (root, context) => {
      ctx = context; lateRoot = root; await gate.promise;
      root.textContent = 'late result'; return async () => { releases++; };
    } }));
    await flush(); env.host.dispose(); assert.equal(ctx.signal.aborted, true);
    gate.resolve(); await env.host.ready; await flush();
    assert.equal(lateRoot.parentNode, null); assert.equal(releases, 1);
    assert.equal(env.elements['module-outlet'].children.length, 0);
  });
  await test('HTML forbids network connections/forms/frames and offers no production SPA', () => {
    assert.match(html, /connect-src 'none'/); assert.match(html, /form-action 'none'/);
    assert.match(html, /frame-src 'none'/); assert.match(html, /worker-src 'none'/);
    assert.match(html, /合成数据演示/); assert.match(html, /不代表正式数据或已通过验收/);
    assert(!html.includes('app.js')); assert(!html.includes('mode=live'));
    assert.match(source, /import\(`\.\/\$\{module.slug\}\/index.js`\)/);
  });
  console.log(`Module demo host: ${count} scenarios PASS (pure Node, synthetic DOM; no network).`);
})().catch(error => { console.error(error); process.exitCode = 1; });
