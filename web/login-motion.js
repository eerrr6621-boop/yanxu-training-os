/* Fixed open spread: only the left headline cycles. Authentication never waits for WebGL. */
(function () {
  'use strict';
  const instances = new WeakMap();
  let rendererModule;
  const defaultLoad = () => (rendererModule ||= import('/scene/login-book-three.js?v=20260906v13r10').catch((error) => { rendererModule = null; throw error; }));
  const FIRST_WAIT = 2600, HOLD = 3000, SWAP_MS = 680, TOPIC_COUNT = 4;
  const noop = () => ({ setPhase() {}, destroy() {} });
  function mount(root, { loadScene = defaultLoad } = {}) {
    if (!root) return noop();
    if (instances.has(root)) return instances.get(root);
    const book = root.querySelector('[data-login-book]'), form = root.querySelector('#login-form');
    if (!book) { root.dataset.motionState = 'unavailable'; return noop(); }
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const fine = window.matchMedia('(pointer: fine)');
    const removers = [];
    let scene = null, destroyed = false, phase = 'idle', observer;
    let topic = 0, transition = null, paused = false, focused = false, dragging = false;
    let timer = 0, frame = 0, generation = 0, deadline = 0, remaining = FIRST_WAIT, previous = 0;
    let pointer = null;
    const view = { pitch: 0, yaw: 0 }, target = { pitch: 0, yaw: 0 };
    const clamp = (value, limit) => Math.max(-limit, Math.min(limit, value));
    function on(node, type, callback, options) {
      if (!node?.addEventListener) return;
      node.addEventListener(type, callback, options);
      removers.push(() => node.removeEventListener(type, callback, options));
    }
    function locked() { return phase === 'loading' || phase === 'success'; }
    function halted() { return paused || focused || dragging || document.hidden || reduced.matches || locked(); }
    function moving() { return ['pitch', 'yaw'].some((key) => Math.abs(view[key] - target[key]) > .0004); }
    function stop() {
      generation += 1;
      if (timer) { remaining = Math.max(0, deadline - window.performance.now()); window.clearTimeout(timer); timer = 0; }
      if (frame) { window.cancelAnimationFrame(frame); frame = 0; }
      previous = 0;
    }
    function attribute(name, value) { if (book.getAttribute(name) !== value) book.setAttribute(name, value); }
    function labels() {
      root.dataset.bookTopic = String(topic);
      root.dataset.motionState = destroyed ? 'destroyed' : !scene ? 'loading-visual' : reduced.matches ? 'reduced' : document.hidden ? 'hidden' : focused ? 'focused' : locked() ? phase : dragging ? 'dragging' : paused ? 'paused' : transition ? 'changing' : 'playing';
      attribute('aria-label', '培训运营、师资推荐、课程交付、项目管理，就用研序。' + (reduced.matches ? '已减少动态效果。' : paused ? '点击或按空格继续文字轮播。' : '点击或按空格暂停文字轮播。'));
      attribute('aria-pressed', String(paused));
      attribute('aria-disabled', String(!scene || reduced.matches || locked()));
    }
    function draw() {
      if (!scene || destroyed || document.hidden) return;
      try { scene.render({ topic, transition: transition ? { next: transition.next, progress: transition.elapsed / SWAP_MS } : null, ...view }); }
      catch (_) { fail(); }
    }
    function frameLoop() {
      if (frame || destroyed || !scene || document.hidden) return;
      const token = generation;
      function tick(now) {
        if (destroyed || token !== generation) return;
        frame = 0;
        if (!root.isConnected) { destroy(); return; }
        if (document.hidden) return;
        const dt = previous ? Math.min(50, Math.max(0, now - previous)) : 16;
        previous = now;
        const blend = reduced.matches ? 1 : 1 - Math.exp(-dt / 110);
        for (const key of ['pitch', 'yaw']) view[key] += (target[key] - view[key]) * blend;
        let finished = false;
        if (transition && !halted()) {
          transition.elapsed = Math.min(SWAP_MS, transition.elapsed + dt);
          if (transition.elapsed === SWAP_MS) {
            topic = transition.next; transition = null; remaining = HOLD; finished = true;
          }
        }
        labels(); draw();
        if (destroyed) return;
        if (finished) waitForNext();
        if ((transition && !halted()) || moving()) frame = window.requestAnimationFrame(tick);
        else previous = 0;
      }
      frame = window.requestAnimationFrame(tick);
    }
    function waitForNext() {
      if (timer || destroyed || !scene || transition || halted()) return;
      const token = generation; deadline = window.performance.now() + remaining;
      timer = window.setTimeout(() => {
        if (destroyed || token !== generation) return;
        timer = 0;
        if (!root.isConnected) { destroy(); return; }
        if (halted()) return;
        transition = { next: (topic + 1) % TOPIC_COUNT, elapsed: 0 };
        labels(); frameLoop();
      }, remaining);
    }
    function sync() {
      if (destroyed) return;
      stop(); labels();
      if (!scene || document.hidden) return;
      draw();
      if ((transition && !halted()) || moving()) frameLoop();
      waitForNext();
    }
    function settleText() {
      if (!transition) return;
      topic = transition.elapsed >= SWAP_MS * .5 ? transition.next : topic;
      transition = null; remaining = HOLD;
    }
    function toggle() {
      if (destroyed || !scene || locked() || reduced.matches) return;
      paused = !paused; settleText(); sync();
    }
    function fail() {
      if (destroyed) return;
      destroy(); root.dataset.motionState = 'unavailable';
      book.setAttribute('aria-disabled', 'true');
      book.setAttribute('aria-label', '培训运营，就用研序');
    }
    function safePick(x, y) {
      try { return scene?.pick(x, y) || null; }
      catch (_) { fail(); return null; }
    }
    function releasePointer() {
      const old = pointer; pointer = null; dragging = false;
      if (old) { try { book.releasePointerCapture?.(old.id); } catch (_) {} }
      book.classList.remove('is-dragging');
    }
    function neutral() { target.pitch = target.yaw = 0; }
    function destroy() {
      if (destroyed) return;
      destroyed = true; stop(); releasePointer(); observer?.disconnect();
      removers.splice(0).forEach((remove) => remove());
      const currentScene = scene; scene = null;
      try { currentScene?.dispose(); } catch (_) { /* Visual teardown cannot block authentication. */ }
      delete root.dataset.bookReady; delete root.dataset.bookEngine;
      instances.delete(root); root.dataset.motionState = 'destroyed';
    }
    const controller = {
      setPhase(value) {
        if (destroyed) return;
        phase = ['idle', 'loading', 'error', 'success'].includes(value) ? value : 'idle';
        root.dataset.motionPhase = phase;
        if (locked()) { releasePointer(); neutral(); settleText(); }
        sync();
      },
      destroy,
    };
    instances.set(root, controller); labels();
    on(book, 'pointerdown', (event) => {
      if (!scene || reduced.matches || (event.button !== undefined && event.button !== 0) || pointer || locked()) return;
      if (!safePick(event.clientX, event.clientY)) return;
      pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, pitch: target.pitch, yaw: target.yaw, moved: false };
      dragging = true; settleText(); sync();
      try { book.setPointerCapture?.(event.pointerId); } catch (_) {}
    });
    on(book, 'pointermove', (event) => {
      if (!scene || reduced.matches || locked() || focused) return;
      if (pointer && pointer.id === event.pointerId) {
        const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y;
        if (Math.hypot(dx, dy) > 6) pointer.moved = true;
        if (pointer.moved) {
          if (event.cancelable) event.preventDefault();
          target.yaw = clamp(pointer.yaw + dx * .0015, .18);
          target.pitch = clamp(pointer.pitch + dy * .0012, .12);
          book.classList.add('is-dragging'); frameLoop();
        }
      } else if (fine.matches && !paused && event.pointerType !== 'touch') {
        const hit = safePick(event.clientX, event.clientY);
        target.yaw = clamp((hit?.x || 0) * .025, .045);
        target.pitch = clamp(-(hit?.y || 0) * .025, .035);
        frameLoop();
      }
    }, { passive: false });
    on(book, 'pointerup', (event) => {
      if (!pointer || pointer.id !== event.pointerId) return;
      const old = pointer; releasePointer(); neutral();
      if (!old.moved) toggle(); else sync();
    });
    for (const type of ['pointercancel', 'lostpointercapture']) on(book, type, (event) => {
      if (pointer && pointer.id === event.pointerId) { releasePointer(); neutral(); sync(); }
    });
    on(book, 'pointerleave', () => { if (!pointer) { neutral(); frameLoop(); } });
    on(book, 'click', (event) => { if (event.detail === 0) toggle(); });
    on(book, 'keydown', (event) => {
      if (!['Enter', ' '].includes(event.key)) return;
      event.preventDefault(); if (!event.repeat) toggle();
    });
    on(form, 'focusin', () => { focused = true; releasePointer(); neutral(); settleText(); sync(); });
    on(form, 'focusout', (event) => { if (!form.contains(event.relatedTarget)) { focused = false; sync(); } });
    on(document, 'visibilitychange', () => { releasePointer(); neutral(); if (!document.hidden) resize(); sync(); });
    on(reduced, 'change', () => {
      neutral(); view.pitch = view.yaw = 0;
      if (reduced.matches) { releasePointer(); settleText(); }
      sync();
    });
    function resize() { if (!destroyed && scene && !document.hidden) { try { scene.resize(); draw(); } catch (_) { fail(); } } }
    on(window, 'resize', resize);
    if (window.ResizeObserver) { observer = new window.ResizeObserver(resize); observer.observe(book); }
    controller.ready = Promise.resolve().then(loadScene).then((module) => {
      if (destroyed || !root.isConnected) { destroy(); return; }
      const created = module.createBookScene(book, { onInvalidate: draw, onContextLost: fail });
      if (destroyed) { created.dispose(); return; }
      scene = created;
      root.dataset.bookReady = 'true'; root.dataset.bookEngine = 'three-r' + created.revision;
      sync();
    }).catch(() => { fail(); });
    return controller;
  }
  window.YanxuLoginMotion = Object.freeze({ mount });
})();
