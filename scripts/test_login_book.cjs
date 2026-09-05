#!/usr/bin/env node
'use strict';

/**
 * Offline lifecycle regression for Yanxu's Three.js login-book controller.
 *
 * This test evaluates the real web/login-motion.js in a deterministic VM and
 * injects a scene stub through mount(root, { loadScene }). It performs no
 * network access, starts no browser and creates no real WebGL context.
 *
 * Usage:
 *   node test-login-three.cjs [project-root]
 *   node test-login-three.cjs --project-root /path/to/yanxu-training
 */

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');

function projectRootFrom(argv) {
  let input = '';
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--help' || value === '-h') {
      console.log('Usage: node test-login-three.cjs [project-root] [--project-root DIR]');
      process.exit(0);
    }
    if (value === '--project-root' || value === '--root') {
      if (!argv[index + 1] || argv[index + 1].startsWith('-')) throw new Error(value + ' requires a directory');
      input = argv[++index];
    } else if (!value.startsWith('-') && !input) input = value;
    else throw new Error('Unknown argument: ' + value);
  }
  const candidate = path.resolve(input || process.cwd());
  const choices = [candidate, path.join(candidate, 'yanxu-training'), path.dirname(candidate)];
  const found = choices.find((root) =>
    fs.existsSync(path.join(root, 'web', 'login-motion.js')) &&
    fs.existsSync(path.join(root, 'web', 'scene', 'login-book-three.js')) &&
    fs.existsSync(path.join(root, 'web', 'scene', 'book-surface.js')));
  if (!found) throw new Error('Could not locate Yanxu Three.js login sources from ' + candidate);
  return found;
}

class FakeEventTarget {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, callback) {
    if (!callback) return;
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(callback);
  }
  removeEventListener(type, callback) { this.listeners.get(type)?.delete(callback); }
  dispatchEvent(event) {
    const value = typeof event === 'string' ? { type: event } : event;
    if (!value || !value.type) throw new TypeError('event.type is required');
    if (value.target == null) value.target = this;
    value.currentTarget = this;
    if (value.defaultPrevented == null) value.defaultPrevented = false;
    if (!value.preventDefault) value.preventDefault = () => { value.defaultPrevented = true; };
    for (const callback of [...(this.listeners.get(value.type) || [])]) callback.call(this, value);
    return !value.defaultPrevented;
  }
  listenerCount(type) { return this.listeners.get(type)?.size || 0; }
  listenerTotal() { return [...this.listeners.values()].reduce((sum, values) => sum + values.size, 0); }
}

class FakeClassList {
  constructor() { this.values = new Set(); }
  set(value) { this.values = new Set(String(value || '').split(/\s+/).filter(Boolean)); }
  add(...values) { values.forEach((value) => this.values.add(value)); }
  remove(...values) { values.forEach((value) => this.values.delete(value)); }
  contains(value) { return this.values.has(value); }
  toString() { return [...this.values].join(' '); }
}

function datasetKey(name) {
  return String(name).replace(/^data-/, '').replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
}

function connectTree(node, connected) {
  node.isConnected = Boolean(connected);
  node.children.forEach((child) => connectTree(child, connected));
}

class FakeElement extends FakeEventTarget {
  constructor(tagName = 'div') {
    super();
    this.tagName = String(tagName).toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.attributes = new Map();
    this.dataset = Object.create(null);
    this.classList = new FakeClassList();
    this.isConnected = false;
    this.clientWidth = 640;
    this.clientHeight = 480;
    this.capturedPointers = new Set();
    this.captureHistory = [];
    this.releaseHistory = [];
    this.throwOnCapture = false;
    this.throwOnRelease = false;
  }
  get className() { return this.classList.toString(); }
  set className(value) { this.classList.set(value); }
  appendChild(child) {
    child.parentNode?.removeChild(child);
    child.parentNode = this;
    this.children.push(child);
    connectTree(child, this.isConnected);
    return child;
  }
  append(...children) { children.forEach((child) => this.appendChild(child)); }
  removeChild(child) {
    const index = this.children.indexOf(child);
    if (index >= 0) this.children.splice(index, 1);
    child.parentNode = null;
    connectTree(child, false);
    return child;
  }
  remove() { this.parentNode?.removeChild(this); }
  contains(node) { return node === this || this.children.some((child) => child.contains(node)); }
  setAttribute(name, value) {
    this.attributes.set(String(name), String(value));
    if (String(name).startsWith('data-')) this.dataset[datasetKey(name)] = String(value);
  }
  getAttribute(name) { return this.attributes.has(String(name)) ? this.attributes.get(String(name)) : null; }
  hasAttribute(name) { return this.attributes.has(String(name)); }
  removeAttribute(name) {
    this.attributes.delete(String(name));
    if (String(name).startsWith('data-')) delete this.dataset[datasetKey(name)];
  }
  matches(selector) {
    const id = /^#([A-Za-z0-9_-]+)$/.exec(selector);
    if (id) return this.getAttribute('id') === id[1];
    const cls = /^\.([A-Za-z0-9_-]+)$/.exec(selector);
    if (cls) return this.classList.contains(cls[1]);
    const attr = /^\[([A-Za-z0-9_-]+)(?:=['"]([^'"]*)['"])?\]$/.exec(selector);
    return Boolean(attr && this.hasAttribute(attr[1]) && (attr[2] == null || this.getAttribute(attr[1]) === attr[2]));
  }
  querySelectorAll(selector) {
    const found = [];
    const visit = (node) => node.children.forEach((child) => {
      if (child.matches(selector)) found.push(child);
      visit(child);
    });
    visit(this);
    return found;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  setPointerCapture(id) {
    if (this.throwOnCapture) throw new Error('synthetic capture failure');
    this.capturedPointers.add(id);
    this.captureHistory.push(id);
  }
  releasePointerCapture(id) {
    if (this.throwOnRelease) throw new Error('synthetic release failure');
    this.capturedPointers.delete(id);
    this.releaseHistory.push(id);
  }
  click() { this.dispatchEvent({ type: 'click', detail: 0 }); }
}

class FakeDocument extends FakeEventTarget {
  constructor() {
    super();
    this.hidden = false;
    this.visibilityState = 'visible';
    this.documentElement = new FakeElement('html');
    this.body = new FakeElement('body');
    this.documentElement.isConnected = true;
    this.documentElement.appendChild(this.body);
  }
  setHidden(hidden) {
    this.hidden = Boolean(hidden);
    this.visibilityState = this.hidden ? 'hidden' : 'visible';
    this.dispatchEvent({ type: 'visibilitychange' });
  }
}

class FakeMediaQueryList extends FakeEventTarget {
  constructor(media, matches = false) { super(); this.media = media; this.matches = Boolean(matches); }
  setMatches(matches) {
    this.matches = Boolean(matches);
    this.dispatchEvent({ type: 'change', matches: this.matches, media: this.media });
  }
}

class FakeScheduler {
  constructor() {
    this.now = 0;
    this.nextTimer = 1;
    this.nextFrame = 100001;
    this.timers = new Map();
    this.frames = new Map();
    this.timerHistory = new Map();
    this.frameHistory = new Map();
    this.maxTimers = 0;
    this.maxFrames = 0;
    this.timerSets = 0;
    this.frameRequests = 0;
  }
  setTimeout(callback, delay = 0) {
    const id = this.nextTimer++;
    const item = { id, callback, due: this.now + Math.max(0, Number(delay) || 0) };
    this.timers.set(id, item);
    this.timerHistory.set(id, item);
    this.timerSets += 1;
    this.maxTimers = Math.max(this.maxTimers, this.timers.size);
    return id;
  }
  clearTimeout(id) { this.timers.delete(id); }
  requestAnimationFrame(callback) {
    const id = this.nextFrame++;
    const item = { id, callback };
    this.frames.set(id, item);
    this.frameHistory.set(id, item);
    this.frameRequests += 1;
    this.maxFrames = Math.max(this.maxFrames, this.frames.size);
    return id;
  }
  cancelAnimationFrame(id) { this.frames.delete(id); }
  pendingTimers() { return this.timers.size; }
  pendingFrames() { return this.frames.size; }
  timerIds() { return [...this.timers.keys()]; }
  frameIds() { return [...this.frames.keys()]; }
  nextTimerDueIn() {
    if (!this.timers.size) return Infinity;
    return Math.min(...[...this.timers.values()].map((item) => item.due)) - this.now;
  }
  advanceTimers(milliseconds) {
    const target = this.now + Math.max(0, Number(milliseconds) || 0);
    let guard = 0;
    while (true) {
      const next = [...this.timers.values()].filter((item) => item.due <= target)
        .sort((a, b) => a.due - b.due || a.id - b.id)[0];
      if (!next) break;
      if (++guard > 10000) throw new Error('timer runaway');
      this.now = next.due;
      this.timers.delete(next.id);
      next.callback();
    }
    this.now = target;
  }
  stepFrame(milliseconds = 50) {
    this.now += Math.max(0, Number(milliseconds) || 0);
    const current = [...this.frames.values()];
    this.frames.clear();
    current.forEach((item) => item.callback(this.now));
  }
  runFrames(limit = 200, milliseconds = 50) {
    let count = 0;
    while (this.frames.size && count < limit) { this.stepFrame(milliseconds); count += 1; }
    if (this.frames.size) throw new Error('RAF did not settle within ' + limit + ' frames');
    return count;
  }
  forceTimer(id) { this.timerHistory.get(id)?.callback(); }
  forceFrame(id, timestamp = this.now) { this.frameHistory.get(id)?.callback(timestamp); }
}

function createDeferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function createSceneKit(options = {}) {
  const stats = { createCalls: 0, scenes: [], hosts: [], hooks: [] };
  const module = {};
  if (!options.missingFactory) {
    module.createBookScene = (host, hooks) => {
      stats.createCalls += 1;
      stats.hosts.push(host);
      stats.hooks.push(hooks);
      if (options.createThrows) throw new Error('synthetic create failure');
      const record = {
        revision: options.revision == null ? '171' : options.revision,
        renders: [],
        resizeCalls: 0,
        pickCalls: [],
        disposeCalls: 0,
        render(state) {
          const snapshot = JSON.parse(JSON.stringify(state));
          record.renders.push(snapshot);
          if (options.renderThrowsAt && record.renders.length >= options.renderThrowsAt) throw new Error('synthetic render failure');
        },
        resize() {
          record.resizeCalls += 1;
          if (options.resizeThrows) throw new Error('synthetic resize failure');
        },
        pick(x, y) {
          record.pickCalls.push([x, y]);
          if (options.pickThrows) throw new Error('synthetic pick failure');
          if (typeof options.pick === 'function') return options.pick(x, y);
          return options.pick === null ? null : { side: x < 0 ? 'left' : 'right' };
        },
        dispose() {
          record.disposeCalls += 1;
          if (options.disposeThrows) throw new Error('synthetic dispose failure');
        },
        invalidate() { hooks.onInvalidate(); },
        contextLost() { hooks.onContextLost(); },
      };
      stats.scenes.push(record);
      return record;
    };
  }
  return { module, stats, get scene() { return stats.scenes[stats.scenes.length - 1] || null; } };
}

function createHarness(source, options = {}) {
  const scheduler = new FakeScheduler();
  const document = new FakeDocument();
  const reduced = new FakeMediaQueryList('(prefers-reduced-motion: reduce)', options.reduced);
  const fine = new FakeMediaQueryList('(pointer: fine)', options.fine !== false);
  const window = new FakeEventTarget();
  const observers = [];
  class ResizeObserver {
    constructor(callback) { this.callback = callback; this.targets = new Set(); this.disconnected = false; observers.push(this); }
    observe(target) { this.targets.add(target); }
    disconnect() { this.targets.clear(); this.disconnected = true; }
    fire() { this.callback([...this.targets].map((target) => ({ target })), this); }
  }
  Object.assign(window, {
    window: null,
    self: null,
    document,
    performance: { now: () => scheduler.now },
    setTimeout: scheduler.setTimeout.bind(scheduler),
    clearTimeout: scheduler.clearTimeout.bind(scheduler),
    requestAnimationFrame: scheduler.requestAnimationFrame.bind(scheduler),
    cancelAnimationFrame: scheduler.cancelAnimationFrame.bind(scheduler),
    matchMedia(query) {
      if (query === reduced.media) return reduced;
      if (query === fine.media) return fine;
      throw new Error('Unexpected media query: ' + query);
    },
    ResizeObserver: options.resizeObserver === false ? undefined : ResizeObserver,
  });
  window.window = window;
  window.self = window;

  const root = new FakeElement('main');
  root.className = 'login-learning';
  let book = null;
  if (options.book !== false) {
    book = new FakeElement('div');
    book.setAttribute('data-login-book', '');
    book.setAttribute('role', 'button');
    book.setAttribute('tabindex', '0');
    root.appendChild(book);
  }
  let form = null;
  let input = null;
  if (options.form !== false) {
    form = new FakeElement('form');
    form.setAttribute('id', 'login-form');
    input = new FakeElement('input');
    input.setAttribute('id', 'login-user');
    form.appendChild(input);
    root.appendChild(form);
  }
  document.body.appendChild(root);

  const sandbox = {
    window,
    self: window,
    globalThis: window,
    document,
    performance: window.performance,
    setTimeout: window.setTimeout,
    clearTimeout: window.clearTimeout,
    requestAnimationFrame: window.requestAnimationFrame,
    cancelAnimationFrame: window.cancelAnimationFrame,
    ResizeObserver: window.ResizeObserver,
    console: { log() {}, warn() {}, error() {} },
    Math,
    Promise,
    WeakMap,
  };
  vm.runInNewContext(source, sandbox, { filename: 'web/login-motion.js', timeout: 2000 });
  const kit = createSceneKit(options.scene || {});
  let loadCalls = 0;
  const rawLoader = options.loadScene || (() => kit.module);
  const loader = () => { loadCalls += 1; return rawLoader(); };
  return {
    window, document, reduced, fine, scheduler, root, book, form, input, observers, kit,
    get loadCalls() { return loadCalls; },
    mount(customLoader = loader) { return window.YanxuLoginMotion.mount(root, { loadScene: customLoader }); },
    api: window.YanxuLoginMotion,
  };
}

function listenerTotal(harness) {
  return harness.window.listenerTotal() + harness.document.listenerTotal() + harness.reduced.listenerTotal() + harness.fine.listenerTotal() +
    harness.root.listenerTotal() + (harness.book?.listenerTotal() || 0) + (harness.form?.listenerTotal() || 0);
}

function event(target, type, values = {}) {
  const value = {
    type,
    defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; },
    ...values,
  };
  target.dispatchEvent(value);
  return value;
}

function key(target, value, repeat = false) {
  return event(target, 'keydown', { key: value, repeat });
}

function pointer(target, type, values = {}) {
  return event(target, type, {
    pointerId: 1,
    clientX: 20,
    clientY: 20,
    button: 0,
    pointerType: 'mouse',
    cancelable: true,
    ...values,
  });
}

function page(harness) { return Number(harness.root.dataset.bookPage); }
function state(harness) { return harness.root.dataset.motionState; }
function latestRender(harness) { return harness.kit.scene?.renders.at(-1) || null; }
async function microtasks(count = 4) { for (let index = 0; index < count; index += 1) await Promise.resolve(); }
async function boot(harness) {
  const controller = harness.mount();
  await controller.ready;
  await microtasks();
  return controller;
}

let assertions = 0;
let passed = 0;
const failures = [];
let section = '';
function check(label, condition, detail = '') {
  assertions += 1;
  if (condition) passed += 1;
  else failures.push({ section, label, detail: String(detail == null ? '' : detail).slice(0, 700) });
}
async function group(name, callback) {
  section = name;
  try { await callback(); }
  catch (error) { check('group completes without unexpected exception', false, error?.stack || error); }
}

async function main() {
  const projectRoot = projectRootFrom(process.argv.slice(2));
  const webRoot = path.join(projectRoot, 'web');
  const motionPath = path.join(webRoot, 'login-motion.js');
  const rendererPath = path.join(webRoot, 'scene', 'login-book-three.js');
  const surfacePath = path.join(webRoot, 'scene', 'book-surface.js');
  const threePath = path.join(webRoot, 'vendor', 'three-r171', 'three.module.min.js');
  const threeCorePath = path.join(webRoot, 'vendor', 'three-r171', 'three.core.min.js');
  const indexPath = path.join(webRoot, 'index.html');
  const motion = fs.readFileSync(motionPath, 'utf8');
  const renderer = fs.readFileSync(rendererPath, 'utf8');
  const index = fs.readFileSync(indexPath, 'utf8');

  await group('static module integration', async () => {
    const syntax = [motionPath, rendererPath, surfacePath, threePath, threeCorePath].map((file) => ({ file, result: spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' }) }));
    syntax.forEach(({ file, result }) => check(path.relative(projectRoot, file) + ' parses', result.status === 0, result.stderr || result.stdout));
    check('mount exposes injectable loadScene option', /function\s+mount\s*\(\s*root\s*,\s*\{\s*loadScene\s*=\s*defaultLoad\s*\}/.test(motion));
    check('default renderer import is same-origin and versioned', /import\(['"]\/scene\/login-book-three\.js\?v=[A-Za-z0-9._-]+['"]\)/.test(motion));
    const imports = [...renderer.matchAll(/\bfrom\s+['"]([^'"]+)['"]/g)].map((match) => match[1]);
    check('renderer imports pinned local Three r171', imports.includes('/vendor/three-r171/three.module.min.js'), imports.join(','));
    check('renderer imports local page surface', imports.includes('/scene/book-surface.js'), imports.join(','));
    check('renderer has no remote or bare specifier', imports.every((value) => value.startsWith('/') && !value.startsWith('//')), imports.join(','));
    imports.forEach((specifier) => check('module dependency exists: ' + specifier, fs.existsSync(path.join(webRoot, specifier.slice(1)))));
    const threeSource = fs.readFileSync(threePath, 'utf8');
    const threeDependencies = [...new Set([...threeSource.matchAll(/\bfrom\s*['"]([^'"]+)['"]/g)].map((match) => match[1]))];
    check('Three facade has one pinned relative core dependency', threeDependencies.length === 1 && threeDependencies[0] === './three.core.min.js', threeDependencies.join(','));
    check('Three transitive core dependency exists', fs.existsSync(path.resolve(path.dirname(threePath), threeDependencies[0] || '__missing__')));
    const three = await import(pathToFileURL(threePath).href);
    check('Three bundle executes as revision 171', three.REVISION === '171', three.REVISION);
    check('Three bundle exports required login primitives', ['WebGLRenderer', 'Scene', 'PerspectiveCamera', 'Raycaster'].every((name) => typeof three[name] === 'function'));
    const mounted = /<script\b[^>]*\bsrc=["'](\/login-motion\.js\?v=([^"']+))["']/i.exec(index);
    const dynamicVersion = /login-book-three\.js\?v=([A-Za-z0-9._-]+)/.exec(motion)?.[1] || '';
    check('index mounts login-motion once', (index.match(/src=["']\/login-motion\.js\?/g) || []).length === 1);
    check('HTML login-motion cache key matches Three generation', Boolean(mounted && dynamicVersion && mounted[2] === dynamicVersion), `${mounted?.[2] || 'missing'} vs ${dynamicVersion || 'missing'}`);
  });

  await group('synchronous facade and delayed unload', async () => {
    const deferred = createDeferred();
    const harness = createHarness(motion, { loadScene: () => deferred.promise });
    const controller = harness.mount();
    check('mount returns controller synchronously', typeof controller?.setPhase === 'function' && typeof controller?.destroy === 'function');
    check('mount exposes readiness Promise', controller.ready && typeof controller.ready.then === 'function');
    check('loader starts in a microtask', harness.loadCalls === 0);
    check('pre-load state is loading-visual', state(harness) === 'loading-visual', state(harness));
    check('pre-load book is aria-disabled', harness.book.getAttribute('aria-disabled') === 'true', harness.book.getAttribute('aria-disabled'));
    check('pre-load creates no timer or RAF', harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
    const same = harness.mount();
    check('repeat mount returns same facade', same === controller);
    await microtasks();
    check('repeat mount invokes loader once', harness.loadCalls === 1, harness.loadCalls);
    const listenerCount = listenerTotal(harness);
    check('mount installs lifecycle listeners before load', listenerCount > 0, listenerCount);
    controller.destroy();
    check('destroy before resolve clears all listeners and schedules', listenerTotal(harness) === 0 && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0, listenerTotal(harness));
    check('destroy before resolve disconnects observer', harness.observers.every((item) => item.disconnected));
    deferred.resolve(harness.kit.module);
    await controller.ready;
    await microtasks();
    check('late module does not create a scene', harness.kit.stats.createCalls === 0, harness.kit.stats.createCalls);
    check('late module cannot overwrite destroyed state', state(harness) === 'destroyed' && !harness.root.dataset.bookReady, JSON.stringify(harness.root.dataset));
  });

  await group('detached root before import resolves', async () => {
    const deferred = createDeferred();
    const harness = createHarness(motion, { loadScene: () => deferred.promise });
    const controller = harness.mount();
    await microtasks();
    harness.root.remove();
    deferred.resolve(harness.kit.module);
    await controller.ready;
    check('detached root does not create scene', harness.kit.stats.createCalls === 0);
    check('detached root self-destroys and removes listeners', state(harness) === 'destroyed' && listenerTotal(harness) === 0, `${state(harness)}/${listenerTotal(harness)}`);
  });

  await group('load and construction failures degrade safely', async () => {
    const rejected = createHarness(motion, { loadScene: () => Promise.reject(new Error('offline rejection')) });
    const rejectedController = rejected.mount();
    await rejectedController.ready;
    check('rejected import becomes unavailable', state(rejected) === 'unavailable', state(rejected));
    check('rejected import keeps login DOM connected', rejected.root.isConnected && rejected.form.isConnected && rejected.book.isConnected);
    check('rejected import leaves no listener/timer/RAF', listenerTotal(rejected) === 0 && rejected.scheduler.pendingTimers() === 0 && rejected.scheduler.pendingFrames() === 0, listenerTotal(rejected));
    check('rejected import has stable fallback ARIA', rejected.book.getAttribute('aria-disabled') === 'true' && rejected.book.getAttribute('aria-label') === '研序培训运营');
    const retryKit = createSceneKit();
    const retry = rejected.api.mount(rejected.root, { loadScene: () => retryKit.module });
    await retry.ready;
    check('failed root can be mounted again', retryKit.stats.createCalls === 1 && rejected.root.dataset.bookReady === 'true', JSON.stringify(rejected.root.dataset));
    retry.destroy();

    for (const [label, scene] of [
      ['missing factory', { missingFactory: true }],
      ['factory throw', { createThrows: true }],
    ]) {
      const harness = createHarness(motion, { scene });
      const controller = await boot(harness);
      check(label + ' becomes unavailable', state(harness) === 'unavailable', state(harness));
      check(label + ' leaves no schedules', harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
      controller.destroy();
    }
  });

  await group('successful load and automatic finite sequence', async () => {
    const harness = createHarness(motion);
    const controller = await boot(harness);
    check('scene created exactly once against book host', harness.kit.stats.createCalls === 1 && harness.kit.stats.hosts[0] === harness.book);
    check('ready flags expose Three revision', harness.root.dataset.bookReady === 'true' && harness.root.dataset.bookEngine === 'three-r171', JSON.stringify(harness.root.dataset));
    check('ready starts on page zero in playing state', page(harness) === 0 && state(harness) === 'playing', `${page(harness)}/${state(harness)}`);
    check('ready enables accessible book', harness.book.getAttribute('aria-disabled') === 'false' && /培训运营/.test(harness.book.getAttribute('aria-label') || ''));
    check('initial state renders once and idles at 0 RAF', harness.kit.scene.renders.length >= 1 && harness.scheduler.pendingFrames() === 0);
    check('initial wait is exactly 1900ms', harness.scheduler.pendingTimers() === 1 && harness.scheduler.nextTimerDueIn() === 1900, harness.scheduler.nextTimerDueIn());
    const seenPages = [page(harness)];
    for (let expected = 1; expected <= 3; expected += 1) {
      const wait = expected === 1 ? 1900 : 1300;
      harness.scheduler.advanceTimers(wait);
      check(`turn ${expected} begins with one RAF and no timer`, harness.scheduler.pendingFrames() === 1 && harness.scheduler.pendingTimers() === 0, `${harness.scheduler.pendingFrames()}/${harness.scheduler.pendingTimers()}`);
      check(`turn ${expected} does not commit before animation`, page(harness) === expected - 1, page(harness));
      const turnRendersBefore = harness.kit.scene.renders.length;
      harness.scheduler.runFrames();
      seenPages.push(page(harness));
      check(`turn ${expected} commits exactly one page`, page(harness) === expected, page(harness));
      const frames = harness.kit.scene.renders.slice(turnRendersBefore).filter((item) => item.turn);
      check(`turn ${expected} reports bounded progress`, frames.length > 1 && frames.every((item) => item.turn.progress >= 0 && item.turn.progress <= 1), frames.map((item) => item.turn?.progress).join(','));
      check(`turn ${expected} settles to zero RAF`, harness.scheduler.pendingFrames() === 0, harness.scheduler.pendingFrames());
      check(`turn ${expected} schedules only expected continuation`, harness.scheduler.pendingTimers() === (expected === 3 ? 0 : 1), harness.scheduler.pendingTimers());
    }
    check('automatic page order is strictly 0-1-2-3', seenPages.join(',') === '0,1,2,3', seenPages.join(','));
    check('final page is complete and named 研序', state(harness) === 'complete' && /^研序。/.test(harness.book.getAttribute('aria-label') || ''), `${state(harness)}/${harness.book.getAttribute('aria-label')}`);
    check('entire run never stacks timer or RAF', harness.scheduler.maxTimers === 1 && harness.scheduler.maxFrames === 1, `${harness.scheduler.maxTimers}/${harness.scheduler.maxFrames}`);
    const counts = [harness.scheduler.timerSets, harness.scheduler.frameRequests, harness.kit.scene.renders.length];
    harness.scheduler.advanceTimers(100000);
    harness.scheduler.stepFrame(100000);
    check('final page never loops or schedules again', page(harness) === 3 && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0 && counts[0] === harness.scheduler.timerSets && counts[1] === harness.scheduler.frameRequests && counts[2] === harness.kit.scene.renders.length);
    controller.destroy();
  });

  await group('pause and resume preserve progress', async () => {
    const waiting = createHarness(motion);
    const waitingController = await boot(waiting);
    const staleTimer = waiting.scheduler.timerIds()[0];
    waiting.scheduler.advanceTimers(700);
    const pause = key(waiting.book, ' ');
    check('Space pauses wait and prevents default', pause.defaultPrevented && state(waiting) === 'paused');
    check('waiting pause clears timer and saves 1200ms', waiting.scheduler.pendingTimers() === 0 && waiting.scheduler.pendingFrames() === 0);
    waiting.scheduler.forceTimer(staleTimer);
    check('stale timer cannot start a turn', page(waiting) === 0 && waiting.scheduler.pendingFrames() === 0);
    waiting.scheduler.advanceTimers(10000);
    key(waiting.book, ' ');
    check('resume restores exact wait remainder', state(waiting) === 'playing' && waiting.scheduler.nextTimerDueIn() === 1200, `${state(waiting)}/${waiting.scheduler.nextTimerDueIn()}`);
    const repeated = key(waiting.book, ' ', true);
    check('repeat key is ignored after preventDefault', repeated.defaultPrevented && state(waiting) === 'playing');
    waitingController.destroy();

    const turning = createHarness(motion);
    const turningController = await boot(turning);
    turning.scheduler.advanceTimers(1900);
    for (let index = 0; index < 8; index += 1) turning.scheduler.stepFrame(50);
    const progress = latestRender(turning).turn.progress;
    const staleFrame = turning.scheduler.frameIds()[0];
    key(turning.book, ' ');
    check('Space pauses an active turn', state(turning) === 'paused' && turning.scheduler.pendingFrames() === 0 && page(turning) === 0, `${state(turning)}/${turning.scheduler.pendingFrames()}`);
    const renders = turning.kit.scene.renders.length;
    turning.scheduler.forceFrame(staleFrame, turning.scheduler.now + 500);
    check('stale cancelled RAF cannot render or commit', turning.kit.scene.renders.length === renders && page(turning) === 0);
    key(turning.book, ' ');
    check('active turn resumes with one RAF', state(turning) === 'turning' && turning.scheduler.pendingFrames() === 1);
    turning.scheduler.stepFrame(16);
    check('resumed turn continues from saved progress', latestRender(turning).turn.progress >= progress, `${progress}/${latestRender(turning).turn.progress}`);
    turning.scheduler.runFrames();
    check('resumed turn reaches next page', page(turning) === 1);
    turningController.destroy();
  });

  await group('manual backward turn and replay', async () => {
    const harness = createHarness(motion);
    const controller = await boot(harness);
    const forward = key(harness.book, 'ArrowRight');
    check('ArrowRight starts manual forward turn', forward.defaultPrevented && harness.scheduler.pendingFrames() === 1 && page(harness) === 0);
    harness.scheduler.runFrames();
    check('manual forward commits page one', page(harness) === 1);
    const before = harness.kit.scene.renders.length;
    const backward = key(harness.book, 'ArrowLeft');
    check('ArrowLeft begins reverse turn and clears auto timer', backward.defaultPrevented && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 1);
    harness.scheduler.runFrames();
    const reverse = harness.kit.scene.renders.slice(before).filter((item) => item.turn).map((item) => item.turn.progress);
    check('reverse progress moves from one toward zero', reverse.length > 2 && reverse[0] >= reverse.at(-1), `${reverse[0]}/${reverse.at(-1)}`);
    check('reverse commits page zero and disables autoplay', page(harness) === 0 && state(harness) === 'manual' && harness.scheduler.pendingTimers() === 0, `${page(harness)}/${state(harness)}`);
    key(harness.book, 'ArrowLeft');
    check('backward boundary at page zero is stable', page(harness) === 0 && harness.scheduler.pendingFrames() === 0 && harness.scheduler.pendingTimers() === 0);
    const home = key(harness.book, 'Home');
    check('Home replay restores automatic initial wait', home.defaultPrevented && page(harness) === 0 && state(harness) === 'playing' && harness.scheduler.nextTimerDueIn() === 1900, `${state(harness)}/${harness.scheduler.nextTimerDueIn()}`);
    controller.setPhase('success');
    check('success moves directly to final page', page(harness) === 3 && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
    key(harness.book, 'ArrowRight');
    check('success phase blocks final-page replay', page(harness) === 3 && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
    controller.destroy();
  });

  await group('pointer tap, drag threshold, capture and cancellation', async () => {
    const tap = createHarness(motion);
    const tapController = await boot(tap);
    pointer(tap.book, 'pointerdown', { pointerId: 7, clientX: 30, clientY: 20 });
    check('valid hit captures pointer and pauses waiting', tap.book.capturedPointers.has(7) && state(tap) === 'dragging' && tap.scheduler.pendingTimers() === 0, `${state(tap)}/${tap.book.captureHistory}`);
    const thresholdMove = pointer(tap.book, 'pointermove', { pointerId: 7, clientX: 36, clientY: 20 });
    check('exact six-pixel movement remains a tap', !thresholdMove.defaultPrevented && !tap.book.classList.contains('is-dragging') && tap.scheduler.pendingFrames() === 0);
    pointer(tap.book, 'pointerup', { pointerId: 7, clientX: 36, clientY: 20 });
    check('tap releases capture and begins hit-side turn', !tap.book.capturedPointers.has(7) && tap.book.releaseHistory.includes(7) && tap.scheduler.pendingFrames() === 1);
    tap.scheduler.runFrames();
    check('right-page tap advances exactly once', page(tap) === 1);
    tapController.destroy();

    const drag = createHarness(motion);
    const dragController = await boot(drag);
    pointer(drag.book, 'pointerdown', { pointerId: 8, clientX: 10, clientY: 10 });
    const moved = pointer(drag.book, 'pointermove', { pointerId: 8, clientX: 1010, clientY: 1010 });
    check('movement beyond threshold prevents default and enters drag class', moved.defaultPrevented && drag.book.classList.contains('is-dragging'));
    drag.scheduler.runFrames();
    const pose = latestRender(drag);
    check('dragged camera pose is clamped', pose.yaw > 0 && pose.yaw <= .4801 && pose.pitch > 0 && pose.pitch <= .2601, `${pose.yaw}/${pose.pitch}`);
    check('drag never turns a page', page(drag) === 0 && !pose.turn);
    pointer(drag.book, 'pointerup', { pointerId: 8, clientX: 1010, clientY: 1010 });
    check('drag release does not turn and releases capture', page(drag) === 0 && !drag.book.capturedPointers.has(8) && !drag.book.classList.contains('is-dragging'));
    check('drag release resumes finite auto wait', drag.scheduler.pendingTimers() === 1);
    dragController.destroy();

    const miss = createHarness(motion, { scene: { pick: null } });
    const missController = await boot(miss);
    pointer(miss.book, 'pointerdown', { pointerId: 9 });
    check('missed raycast does not capture or pause', miss.book.capturedPointers.size === 0 && state(miss) === 'playing' && miss.scheduler.pendingTimers() === 1);
    pointer(miss.book, 'pointerdown', { pointerId: 10, button: 2 });
    check('non-primary button is ignored', miss.book.capturedPointers.size === 0 && miss.kit.scene.pickCalls.length === 1);
    missController.destroy();

    const cancel = createHarness(motion);
    const cancelController = await boot(cancel);
    pointer(cancel.book, 'pointerdown', { pointerId: 11 });
    pointer(cancel.book, 'pointercancel', { pointerId: 12 });
    check('unrelated pointercancel does not cancel active capture', cancel.book.capturedPointers.has(11) && state(cancel) === 'dragging', `${[...cancel.book.capturedPointers]}/${state(cancel)}`);
    pointer(cancel.book, 'pointercancel', { pointerId: 11 });
    check('matching pointercancel releases without turning', !cancel.book.capturedPointers.has(11) && page(cancel) === 0 && !cancel.book.classList.contains('is-dragging'));
    check('matching pointercancel resumes auto wait', cancel.scheduler.pendingTimers() === 1 && cancel.scheduler.pendingFrames() === 0);
    cancelController.destroy();

    const lost = createHarness(motion);
    const lostController = await boot(lost);
    pointer(lost.book, 'pointerdown', { pointerId: 21 });
    pointer(lost.book, 'lostpointercapture', { pointerId: 20 });
    check('stale lostpointercapture does not cancel current pointer', lost.book.capturedPointers.has(21) && state(lost) === 'dragging', `${[...lost.book.capturedPointers]}/${state(lost)}`);
    pointer(lost.book, 'lostpointercapture', { pointerId: 21 });
    check('matching lostpointercapture returns to waiting without a turn', page(lost) === 0 && state(lost) === 'playing' && lost.scheduler.pendingTimers() === 1);
    lostController.destroy();
  });

  await group('visibility, focus and reduced motion', async () => {
    const visibility = createHarness(motion);
    const visibilityController = await boot(visibility);
    visibility.scheduler.advanceTimers(500);
    visibility.document.setHidden(true);
    check('hidden page pauses with no active scheduling', state(visibility) === 'hidden' && visibility.scheduler.pendingTimers() === 0 && visibility.scheduler.pendingFrames() === 0, state(visibility));
    const hiddenRenders = visibility.kit.scene.renders.length;
    visibility.kit.scene.invalidate();
    check('scene invalidation cannot render while hidden', visibility.kit.scene.renders.length === hiddenRenders);
    visibility.scheduler.advanceTimers(5000);
    visibility.document.setHidden(false);
    check('visibility restore resizes scene', visibility.kit.scene.resizeCalls === 1, visibility.kit.scene.resizeCalls);
    check('visibility restore resumes exact 1400ms wait', visibility.scheduler.nextTimerDueIn() === 1400, visibility.scheduler.nextTimerDueIn());
    visibilityController.destroy();

    const focus = createHarness(motion);
    const focusController = await boot(focus);
    focus.scheduler.advanceTimers(400);
    event(focus.form, 'focusin', { target: focus.input });
    check('form focus pauses auto sequence', state(focus) === 'focused' && focus.scheduler.pendingTimers() === 0, state(focus));
    event(focus.form, 'focusout', { target: focus.input, relatedTarget: focus.input });
    check('focus movement within form remains paused', state(focus) === 'focused' && focus.scheduler.pendingTimers() === 0);
    event(focus.form, 'focusout', { target: focus.input, relatedTarget: null });
    check('focus leaving form resumes exact remainder', state(focus) === 'playing' && focus.scheduler.nextTimerDueIn() === 1500, `${state(focus)}/${focus.scheduler.nextTimerDueIn()}`);
    focusController.destroy();

    const reduced = createHarness(motion, { reduced: true });
    const reducedController = await boot(reduced);
    check('initial reduced motion snaps to final page', page(reduced) === 3 && state(reduced) === 'reduced', `${page(reduced)}/${state(reduced)}`);
    check('initial reduced motion has no timer or RAF', reduced.scheduler.pendingTimers() === 0 && reduced.scheduler.pendingFrames() === 0);
    key(reduced.book, 'ArrowLeft');
    check('reduced keyboard backward navigation is instantaneous', page(reduced) === 2 && reduced.scheduler.pendingFrames() === 0 && reduced.scheduler.pendingTimers() === 0);
    pointer(reduced.book, 'pointerdown', { pointerId: 31, clientX: 10 });
    pointer(reduced.book, 'pointerup', { pointerId: 31, clientX: 10 });
    check('reduced pointer tap is instantaneous', page(reduced) === 3 && reduced.scheduler.pendingFrames() === 0 && reduced.scheduler.pendingTimers() === 0);
    reducedController.destroy();

    const changing = createHarness(motion);
    const changingController = await boot(changing);
    changing.scheduler.advanceTimers(1900);
    changing.scheduler.stepFrame(100);
    changing.reduced.setMatches(true);
    check('enabling reduced motion mid-turn snaps final and clears work', page(changing) === 3 && state(changing) === 'reduced' && changing.scheduler.pendingTimers() === 0 && changing.scheduler.pendingFrames() === 0, `${page(changing)}/${state(changing)}`);
    changing.reduced.setMatches(false);
    check('disabling reduced motion does not restart completed sequence', page(changing) === 3 && state(changing) === 'complete' && changing.scheduler.pendingTimers() === 0 && changing.scheduler.pendingFrames() === 0);
    changingController.destroy();
  });

  await group('phase buffering and transitions', async () => {
    const deferred = createDeferred();
    const harness = createHarness(motion, { loadScene: () => deferred.promise });
    const controller = harness.mount();
    controller.setPhase('loading');
    check('loading phase is recorded before scene resolves', harness.root.dataset.motionPhase === 'loading' && state(harness) === 'loading-visual', `${harness.root.dataset.motionPhase}/${state(harness)}`);
    await microtasks();
    deferred.resolve(harness.kit.module);
    await controller.ready;
    check('latest buffered loading phase stops initial timer', state(harness) === 'loading' && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0, state(harness));
    controller.setPhase('error');
    check('error phase resumes auto sequence', harness.root.dataset.motionPhase === 'error' && state(harness) === 'playing' && harness.scheduler.nextTimerDueIn() === 1900, `${state(harness)}/${harness.scheduler.nextTimerDueIn()}`);
    controller.setPhase('nonsense');
    check('unknown phase normalizes to idle', harness.root.dataset.motionPhase === 'idle' && state(harness) === 'playing');
    controller.setPhase('success');
    check('success phase finishes immediately with no work', page(harness) === 3 && harness.root.dataset.motionPhase === 'success' && harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
    pointer(harness.book, 'pointerdown', { pointerId: 40 });
    check('success phase rejects pointer interaction', harness.book.capturedPointers.size === 0 && page(harness) === 3);
    controller.destroy();

    const earlySuccess = createHarness(motion, { loadScene: () => Promise.resolve(createSceneKit().module) });
    const earlyController = earlySuccess.mount();
    earlyController.setPhase('success');
    await earlyController.ready;
    check('success before module load applies after readiness', page(earlySuccess) === 3 && earlySuccess.scheduler.pendingTimers() === 0 && earlySuccess.scheduler.pendingFrames() === 0, page(earlySuccess));
    earlyController.destroy();

    const guarded = createHarness(motion);
    const guardedController = await boot(guarded);
    pointer(guarded.book, 'pointerdown', { pointerId: 41 });
    check('phase guard fixture owns a pointer before loading', guarded.book.capturedPointers.has(41));
    guardedController.setPhase('loading');
    check('loading phase releases active pointer and clears schedules', guarded.book.capturedPointers.size === 0 && state(guarded) === 'loading' && guarded.scheduler.pendingTimers() === 0 && guarded.scheduler.pendingFrames() === 0, `${state(guarded)}/${guarded.scheduler.pendingTimers()}/${guarded.scheduler.pendingFrames()}`);
    for (const guardedKey of [' ', 'Enter', 'ArrowLeft', 'ArrowRight', 'Home']) {
      const pressed = key(guarded.book, guardedKey);
      check(`loading phase prevents ${JSON.stringify(guardedKey)} without changing page`, pressed.defaultPrevented && page(guarded) === 0 && guarded.scheduler.pendingTimers() === 0 && guarded.scheduler.pendingFrames() === 0, `${page(guarded)}/${guarded.scheduler.pendingTimers()}/${guarded.scheduler.pendingFrames()}`);
    }
    guardedController.setPhase('error');
    check('failed login resumes rather than inheriting hidden pause state', state(guarded) === 'playing' && guarded.scheduler.nextTimerDueIn() === 1900, `${state(guarded)}/${guarded.scheduler.nextTimerDueIn()}`);
    guardedController.destroy();
  });

  await group('context loss and scene runtime failures', async () => {
    const context = createHarness(motion);
    const contextController = await boot(context);
    context.kit.scene.contextLost();
    check('context loss permanently degrades to static fallback', state(context) === 'unavailable' && context.book.getAttribute('aria-disabled') === 'true', state(context));
    check('context loss disposes scene exactly once', context.kit.scene.disposeCalls === 1, context.kit.scene.disposeCalls);
    check('context loss clears listeners, timers and RAF', listenerTotal(context) === 0 && context.scheduler.pendingTimers() === 0 && context.scheduler.pendingFrames() === 0, listenerTotal(context));
    contextController.destroy();
    check('destroy after context loss remains idempotent', context.kit.scene.disposeCalls === 1);

    const renderFailure = createHarness(motion, { scene: { renderThrowsAt: 1 } });
    const renderController = await boot(renderFailure);
    check('render failure degrades and disposes', state(renderFailure) === 'unavailable' && renderFailure.kit.scene.disposeCalls === 1, state(renderFailure));
    check('render failure cannot leave schedules', renderFailure.scheduler.pendingTimers() === 0 && renderFailure.scheduler.pendingFrames() === 0);
    renderController.destroy();

    const resizeFailure = createHarness(motion, { scene: { resizeThrows: true } });
    const resizeController = await boot(resizeFailure);
    resizeFailure.window.dispatchEvent({ type: 'resize' });
    check('resize failure degrades and disposes', state(resizeFailure) === 'unavailable' && resizeFailure.kit.scene.disposeCalls === 1, state(resizeFailure));
    resizeController.destroy();

    const pickFailure = createHarness(motion, { scene: { pickThrows: true } });
    const pickController = await boot(pickFailure);
    let pickError = null;
    try { pointer(pickFailure.book, 'pointerdown', { pointerId: 71 }); }
    catch (error) { pickError = error; }
    check('raycast failure is contained by optional visual boundary', pickError == null, pickError?.message || '');
    check('raycast failure degrades and disposes scene', state(pickFailure) === 'unavailable' && pickFailure.kit.scene.disposeCalls === 1, `${state(pickFailure)}/${pickFailure.kit.scene.disposeCalls}`);
    pickController.destroy();
  });

  await group('destroy releases every owned lifecycle resource', async () => {
    const harness = createHarness(motion);
    const controller = await boot(harness);
    harness.scheduler.advanceTimers(1900);
    harness.scheduler.stepFrame(200);
    const staleFrame = harness.scheduler.frameIds()[0];
    pointer(harness.book, 'pointerdown', { pointerId: 51 });
    check('fixture has active listeners and capture before destroy', listenerTotal(harness) > 0 && harness.book.capturedPointers.has(51));
    const renders = harness.kit.scene.renders.length;
    controller.destroy();
    check('destroy disposes scene exactly once', harness.kit.scene.disposeCalls === 1, harness.kit.scene.disposeCalls);
    check('destroy clears all listener registrations', listenerTotal(harness) === 0, listenerTotal(harness));
    check('destroy disconnects ResizeObserver', harness.observers.length === 1 && harness.observers[0].disconnected);
    check('destroy cancels timers and RAF', harness.scheduler.pendingTimers() === 0 && harness.scheduler.pendingFrames() === 0);
    check('destroy releases captured pointer and drag class', harness.book.capturedPointers.size === 0 && !harness.book.classList.contains('is-dragging'));
    check('destroy removes ready flags and exposes destroyed state', state(harness) === 'destroyed' && !harness.root.dataset.bookReady && !harness.root.dataset.bookEngine, JSON.stringify(harness.root.dataset));
    harness.scheduler.forceFrame(staleFrame, harness.scheduler.now + 1000);
    check('stale RAF after destroy cannot render', harness.kit.scene.renders.length === renders);
    controller.setPhase('error');
    controller.destroy();
    check('destroy and public methods are idempotent', harness.kit.scene.disposeCalls === 1 && state(harness) === 'destroyed');
    const replacement = harness.mount(() => createSceneKit().module);
    check('destroy removes WeakMap entry for a fresh mount', replacement !== controller);
    replacement.destroy();
  });

  await group('destroy remains safe when injected scene dispose throws', async () => {
    const harness = createHarness(motion, { scene: { disposeThrows: true } });
    const controller = await boot(harness);
    let thrown = null;
    try { controller.destroy(); } catch (error) { thrown = error; }
    check('controller destroy contains scene disposal failure', thrown == null, thrown?.message || '');
    check('dispose failure still clears ready flags and marks destroyed', state(harness) === 'destroyed' && !harness.root.dataset.bookReady && !harness.root.dataset.bookEngine, JSON.stringify(harness.root.dataset));
    const replacement = harness.mount(() => createSceneKit().module);
    check('dispose failure still removes stale WeakMap controller', replacement !== controller);
    replacement.destroy();
  });

  await group('optional DOM and defensive no-op paths', async () => {
    const noForm = createHarness(motion, { form: false });
    const noFormController = await boot(noForm);
    check('missing form does not block visual scene', noForm.kit.stats.createCalls === 1 && noForm.root.dataset.bookReady === 'true');
    noFormController.destroy();

    const noBook = createHarness(motion, { book: false });
    const noBookController = noBook.mount();
    check('missing book returns safe no-op controller', typeof noBookController.setPhase === 'function' && typeof noBookController.destroy === 'function');
    check('missing book does not invoke loader', noBook.loadCalls === 0 && state(noBook) === 'unavailable');
    noBookController.setPhase('success'); noBookController.destroy();

    const nullController = noBook.api.mount(null, { loadScene: () => { throw new Error('must not load'); } });
    check('null root returns safe no-op controller', typeof nullController.setPhase === 'function' && typeof nullController.destroy === 'function');
    nullController.setPhase('loading'); nullController.destroy();

    const captureFailure = createHarness(motion);
    captureFailure.book.throwOnCapture = true;
    captureFailure.book.throwOnRelease = true;
    const captureController = await boot(captureFailure);
    let captureError = null;
    try {
      pointer(captureFailure.book, 'pointerdown', { pointerId: 61 });
      pointer(captureFailure.book, 'pointercancel', { pointerId: 61 });
    } catch (error) { captureError = error; }
    check('pointer capture platform exceptions are contained', captureError == null, captureError?.message || '');
    captureController.destroy();
  });

  const report = {
    ok: failures.length === 0,
    assertions,
    passed,
    failed: failures.length,
    project_root: projectRoot,
    scope: 'offline injected Three.js login controller; no browser/network/WebGL',
    failures,
  };
  console.log(JSON.stringify(report, null, 2));
  return report.ok ? 0 : 1;
}

main().then((code) => { process.exitCode = code; }).catch((error) => {
  console.error(JSON.stringify({ ok: false, fatal: String(error?.stack || error).slice(0, 1600) }, null, 2));
  process.exitCode = 2;
});
