#!/usr/bin/env node
'use strict';
// Deterministic navigation and image decode tests; no browser, network or WebGL.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
function navigationHarness(previous = 'https://local.test/', settings = {}) {
  const links = [0, 1].map(() => ({href: 'https://local.test/', target: '', hasAttribute: () => false,
    addEventListener(type, fn) { this[type] = fn; }}));
  const entries = [{key: 'home', index: 0, url: previous}, {key: 'updates', index: 1, url: 'https://local.test/updates.html'}];
  let backCalls = 0;
  const window = {location: {origin: 'https://local.test'},
    navigation: {currentEntry: entries[1], entries: () => entries},
    history: {back() { backCalls++; if (settings.backThrows) throw new Error('navigation blocked'); }},
    addEventListener(type, fn) { this[type] = fn; }};
  if (settings.configure) settings.configure(window, entries, links);
  vm.runInNewContext(read('web/return-home.js'), {window, document: {querySelectorAll: () => links}, URL});
  return {window, links, get backCalls() { return backCalls; }, click(overrides = {}, index = 0) {
    const event = {button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...overrides};
    links[index].click(event); return event;
  }};
}
(async () => {
  for (const home of ['https://local.test/', 'https://local.test/index.html']) {
    const h = navigationHarness(home);
    check(h.click().defaultPrevented && h.backCalls === 1, 'normal home entry uses back: ' + home);
    check(h.click({}, 1).defaultPrevented && h.backCalls === 1, 'double click cannot skip past home');
    h.window.pageshow({persisted: true});
    check(h.click({}, 1).defaultPrevented && h.backCalls === 2, 'forward then return works again');
  }
  for (const url of ['https://other.test/', 'https://local.test/materials.html', 'https://local.test/#/dashboard',
    'https://local.test/?tab=login', 'not a url', undefined]) {
    const h = navigationHarness(url, url === undefined ? {configure(w, e) { e[0].url = undefined; }} : {});
    check(!h.click().defaultPrevented && h.backCalls === 0, 'unverified previous entry uses normal link: ' + url);
  }
  const variants = [
    w => { delete w.navigation; }, w => { w.navigation.currentEntry = null; },
    w => { w.navigation.entries = () => []; }, w => { w.navigation.entries = () => { throw new Error('unsupported'); }; },
    (w, e) => { e[0].index = -2; }, (w, e) => { w.navigation.currentEntry = {...e[1], key: 'missing'}; },
    (w, e, links) => { links[0].href = 'https://other.test/'; },
    (w, e, links) => { links[0].href = 'https://local.test/?new=1'; },
  ];
  for (const configure of variants) {
    const h = navigationHarness(undefined, {configure});
    check(!h.click().defaultPrevented && !h.backCalls, 'unsupported/incomplete history keeps link');
  }
  for (const modifiers of [{button: 1}, {metaKey: true}, {ctrlKey: true}, {shiftKey: true}, {altKey: true}, {defaultPrevented: true}]) {
    const h = navigationHarness(); h.click(modifiers);
    check(!h.backCalls, 'modified/cancelled click keeps native behavior');
  }
  for (const attribute of ['target', 'download']) {
    const h = navigationHarness();
    if (attribute === 'target') h.links[0].target = '_blank'; else h.links[0].hasAttribute = () => true;
    check(!h.click().defaultPrevented && !h.backCalls, 'explicit new tab/download is not intercepted');
  }
  const rejected = navigationHarness(undefined, {backThrows: true});
  check(!rejected.click().defaultPrevented && !rejected.click().defaultPrevented && rejected.backCalls === 2, 'failed back leaves usable normal link');
  const keyboard = navigationHarness();
  check(keyboard.click({detail: 0}).defaultPrevented && keyboard.backCalls === 1, 'keyboard-generated anchor click works');

  const {loadBookArtwork} = await import('data:text/javascript;base64,' + Buffer.from(read('web/scene/book-artwork.js')).toString('base64'));
  function loaderHarness(assets = {a: '/a?v=1', b: '/b?v=1'}, options = {}) {
    const images = [], timers = new Map(); let next = 0;
    const promise = loadBookArtwork(assets, {timeout: 5000,
      setTimer(fn, delay) { check(delay === 5000, 'bounded image/decode wait'); timers.set(++next, fn); return next; },
      clearTimer(id) { timers.delete(id); },
      createImage() {
        if (options.createThrows) throw new Error('unavailable Image');
        let resolveDecode, rejectDecode;
        const image = {complete: false, naturalWidth: 960, naturalHeight: 240,
          decode: () => new Promise((yes, no) => {resolveDecode = yes; rejectDecode = no;}),
          load() { this.onload?.(); }, error() { this.onerror?.(); },
          decoded() { resolveDecode?.(); }, badDecode() { rejectDecode?.(new Error('bad image')); }};
        if (options.cached) { image.complete = true; image.decode = () => Promise.resolve(); }
        if (options.noDecode) delete image.decode;
        images.push(image); return image;
      }});
    return {images, timers, promise};
  }
  const h = loaderHarness(); let finished = false; h.promise.then(() => {finished = true;});
  check(h.images.length === 2 && h.images[0].src === '/a?v=1', 'batch starts all local requests in parallel');
  h.images[0].load(); h.images[1].load(); h.images[0].decoded(); await flush();
  check(!finished, 'partial image decode never exposes a partial batch');
  h.images[1].decoded(); const artwork = await h.promise;
  check(artwork.a === h.images[0] && artwork.b === h.images[1] && Object.isFrozen(artwork), 'complete decoded batch is stable');
  check(!h.timers.size && h.images.every(x => !x.onload && !x.onerror), 'settled batch clears timers and handlers');
  for (const mode of ['error', 'decode', 'timeout', 'decode-timeout']) {
    const failed = loaderHarness();
    failed.images[0].load(); failed.images[0].decoded(); await flush();
    const lateLoad = failed.images[1].onload;
    if (mode.startsWith('decode')) failed.images[1].load();
    if (mode === 'error') failed.images[1].error();
    else if (mode === 'decode') failed.images[1].badDecode();
    else for (const timer of [...failed.timers.values()]) timer();
    const result = await failed.promise;
    lateLoad(); failed.images[1].decoded(); await flush();
    check(result.a === failed.images[0] && !result.b && !failed.timers.size && !failed.images[1].onload,
      'failed/late image is a stable fallback: ' + mode);
  }
  const cached = loaderHarness(undefined, {cached: true});
  check(Object.keys(await cached.promise).length === 2 && !cached.timers.size, 'warm cache resolves without load event');
  const legacy = loaderHarness(undefined, {noDecode: true}); legacy.images.forEach(i => i.load());
  check(Object.keys(await legacy.promise).length === 2, 'legacy Image without decode remains supported');
  const missing = loaderHarness(undefined, {createThrows: true});
  check(Object.keys(await missing.promise).length === 0 && !missing.timers.size, 'unavailable Image is bounded and safe');
  check(Object.keys(await loadBookArtwork({})).length === 0, 'empty batch resolves');

  const renderer = read('web/scene/login-book-three.js'), css = read('web/v13.css'), html = read('web/updates.html');
  check(!renderer.includes('new Image()') && !renderer.includes('onInvalidate()'), 'visible scene has no late-image repaints');
  check(renderer.indexOf('material.bumpMap = relief') < renderer.lastIndexOf('resize();'), 'final paper material precedes first full render');
  check(renderer.includes('viewport.width === width && viewport.height === height && viewport.ratio === ratio) return'), 'unchanged viewport does not clear/reallocate the drawing buffer');
  check(/\.book-three-canvas \{[^}]*opacity: 0/.test(css) && /data-book-ready="true"\] \.book-three-canvas \{ opacity: 1/.test(css), 'canvas appears only after complete first frame');
  check(/data-motion-state="loading-visual"\] \.book-fallback \{ visibility: hidden/.test(css), 'loading does not flash differently shaped fallback');
  check((html.match(/data-return-home/g) || []).length === 2 && html.includes('/return-home.js?v=20260907-return'), 'both home links share safe return handler');
  check(!/beforeunload|addEventListener\(['"]unload/.test(read('web/login-motion.js') + read('web/return-home.js')), 'no cache-blocking unload listener');
  console.log(JSON.stringify({ok: true, suite: 'safe return navigation and complete book artwork', checks}));
})().catch(error => {console.error(error); process.exitCode = 1;});
