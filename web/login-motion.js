/* Synchronous login facade; Three.js loads lazily and never gates authentication. */
(function () {
  'use strict';
  const instances = new WeakMap();
  let rendererModule;
  const defaultLoad = () => (rendererModule ||= import('/scene/login-book-three.js?v=20260906v13three').catch((error) => { rendererModule = null; throw error; }));
  const FIRST_WAIT = 1900, HOLD = 1300, TURN_MS = 1450;
  const noop = () => ({ setPhase() {}, destroy() {} });
  function mount(root, { loadScene = defaultLoad } = {}) {
    if (!root) return noop();
    if (instances.has(root)) return instances.get(root);
    const book = root.querySelector('[data-login-book]'), form = root.querySelector('#login-form');
    if (!book) { root.dataset.motionState = 'unavailable'; return noop(); }
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const fine = window.matchMedia('(pointer: fine)');
    const titles = ['培训运营', '师资推荐', '课程交付', '研序'];
    const removers = [];
    let scene = null, destroyed = false, phase = 'idle', observer;
    let page = 0, turn = null, auto = true, paused = false, focused = false, dragging = false;
    let timer = 0, frame = 0, generation = 0, deadline = 0, remaining = FIRST_WAIT, previous = 0;
    let pointer = null;
    const view = { pitch: 0, yaw: 0, hover: 0 }, target = { pitch: 0, yaw: 0, hover: 0 };
    const clamp = (value, limit) => Math.max(-limit, Math.min(limit, value));
    function on(targetNode, type, callback, options) {
      if (!targetNode?.addEventListener) return;
      targetNode.addEventListener(type, callback, options);
      removers.push(() => targetNode.removeEventListener(type, callback, options));
    }
    function halted() { return paused || focused || dragging || document.hidden || reduced.matches || phase === 'loading' || phase === 'success'; }
    function moving() { return ['pitch', 'yaw', 'hover'].some((key) => Math.abs(view[key] - target[key]) > .0004); }
    function stop() {
      generation += 1;
      if (timer) { remaining = Math.max(0, deadline - window.performance.now()); window.clearTimeout(timer); timer = 0; }
      if (frame) { window.cancelAnimationFrame(frame); frame = 0; }
      previous = 0;
    }
    function labels() {
      root.dataset.bookPage = String(page);
      root.dataset.motionState = destroyed ? 'destroyed' : !scene ? 'loading-visual' : reduced.matches ? 'reduced' : document.hidden ? 'hidden' : focused ? 'focused' : phase === 'loading' ? 'loading' : dragging ? 'dragging' : paused ? 'paused' : turn ? 'turning' : page === 3 ? 'complete' : auto ? 'playing' : 'manual';
      const action = page === 3 ? '点击右页可重新观看' : paused ? '空格继续' : '空格暂停';
      book.setAttribute('aria-label', titles[page] + '。拖动调整视角，点击左右书页翻动，' + action);
      book.setAttribute('aria-disabled', String(!scene));
    }
    function draw() {
      if (!scene || destroyed || document.hidden) return;
      try { scene.render({ page, turn: turn ? { index: turn.index, progress: turn.from + (turn.to - turn.from) * turn.elapsed / TURN_MS } : null, ...view }); }
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
        const blend = reduced.matches ? 1 : 1 - Math.exp(-dt / 95);
        for (const key of ['pitch', 'yaw', 'hover']) view[key] += (target[key] - view[key]) * blend;
        let finished = false;
        if (turn && !halted()) {
          turn.elapsed = Math.min(TURN_MS, turn.elapsed + dt);
          if (turn.elapsed === TURN_MS) {
            page = turn.destination; turn = null; remaining = HOLD; finished = true;
          }
        }
        labels(); draw();
        if (destroyed) return;
        if (finished) waitForNext();
        if ((turn && !halted()) || moving()) frame = window.requestAnimationFrame(tick);
        else previous = 0;
      }
      frame = window.requestAnimationFrame(tick);
    }
    function waitForNext() {
      if (timer || destroyed || !scene || turn || page === 3 || !auto || halted()) return;
      const token = generation; deadline = window.performance.now() + remaining;
      timer = window.setTimeout(() => {
        if (destroyed || token !== generation) return;
        timer = 0;
        if (!root.isConnected) { destroy(); return; }
        beginTurn(1, true);
      }, remaining);
    }
    function sync() {
      if (destroyed) return;
      stop(); labels();
      if (!scene || document.hidden) return;
      draw();
      if ((turn && !halted()) || moving()) frameLoop();
      waitForNext();
    }
    function finish() {
      stop(); turn = null; page = 3; auto = false; target.hover = view.hover = 0;
      sync();
    }
    function replay() {
      if (destroyed || !scene || phase === 'loading' || phase === 'success') return;
      stop(); page = 0; turn = null; paused = false; auto = true; remaining = FIRST_WAIT;
      if (reduced.matches) finish(); else sync();
    }
    function beginTurn(direction, automatic = false) {
      if (destroyed || !scene || turn || phase === 'loading' || phase === 'success') return;
      if (direction > 0 && page === 3) { if (!automatic) replay(); return; }
      if (direction < 0 && page === 0) return;
      stop(); paused = false; target.hover = view.hover = 0;
      if (direction < 0) auto = false;
      const destination = page + direction;
      if (reduced.matches) { page = destination; auto = false; sync(); return; }
      turn = { index: direction > 0 ? page : page - 1, from: direction > 0 ? 0 : 1, to: direction > 0 ? 1 : 0, elapsed: 0, destination };
      sync();
    }
    function fail() {
      if (destroyed) return;
      destroy(); root.dataset.motionState = 'unavailable';
      book.setAttribute('aria-disabled', 'true');
      book.setAttribute('aria-label', '研序培训运营');
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
    function destroy() {
      if (destroyed) return;
      destroyed = true; stop(); releasePointer(); observer?.disconnect();
      removers.splice(0).forEach((remove) => remove());
      const currentScene = scene; scene = null;
      try { currentScene?.dispose(); } catch (_) { /* A visual teardown cannot block authentication. */ }
      delete root.dataset.bookReady; delete root.dataset.bookEngine;
      instances.delete(root); root.dataset.motionState = 'destroyed';
    }
    const controller = {
      setPhase(value) {
        if (destroyed) return;
        phase = ['idle', 'loading', 'error', 'success'].includes(value) ? value : 'idle';
        root.dataset.motionPhase = phase;
        if (phase === 'loading' || phase === 'success') { releasePointer(); target.hover = 0; }
        if (phase === 'success') finish(); else sync();
      },
      destroy,
    };
    instances.set(root, controller); labels();
    on(book, 'pointerdown', (event) => {
      if (!scene || (event.button !== undefined && event.button !== 0) || pointer || phase === 'loading' || phase === 'success') return;
      const hit = safePick(event.clientX, event.clientY);
      if (!hit) return;
      pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, pitch: target.pitch, yaw: target.yaw, moved: false, side: hit.side };
      dragging = true; target.hover = 0; sync();
      try { book.setPointerCapture?.(event.pointerId); } catch (_) {}
    });
    on(book, 'pointermove', (event) => {
      if (!scene || reduced.matches) return;
      if (pointer && pointer.id === event.pointerId) {
        const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y;
        if (Math.hypot(dx, dy) > 6) pointer.moved = true;
        if (pointer.moved) {
          if (event.cancelable) event.preventDefault();
          target.yaw = clamp(pointer.yaw + dx * .0027, .48);
          target.pitch = clamp(pointer.pitch + dy * .0023, .26);
          book.classList.add('is-dragging'); frameLoop();
        }
      } else if (fine.matches && !focused && event.pointerType !== 'touch') {
        const hit = safePick(event.clientX, event.clientY);
        target.hover = hit?.side === 'right' && page < 3 && !turn ? 1 : 0;
        frameLoop();
      }
    }, { passive: false });
    on(book, 'pointerup', (event) => {
      if (!pointer || pointer.id !== event.pointerId) return;
      const old = pointer; releasePointer();
      if (!old.moved) beginTurn(old.side === 'left' ? -1 : 1);
      sync();
    });
    on(book, 'pointercancel', (event) => { if (pointer && pointer.id === event.pointerId) { releasePointer(); sync(); } });
    on(book, 'lostpointercapture', (event) => { if (pointer && pointer.id === event.pointerId) { releasePointer(); sync(); } });
    on(book, 'pointerleave', () => { if (!pointer) { target.hover = 0; frameLoop(); } });
    on(book, 'click', (event) => { if (event.detail === 0) beginTurn(1); });
    on(book, 'keydown', (event) => {
      if (!['Enter', ' ', 'ArrowLeft', 'ArrowRight', 'Home'].includes(event.key)) return;
      event.preventDefault(); if (event.repeat || phase === 'loading' || phase === 'success') return;
      if (event.key === ' ') { paused = !paused; sync(); }
      else if (event.key === 'Home') replay();
      else beginTurn(event.key === 'ArrowLeft' ? -1 : 1);
    });
    on(form, 'focusin', () => { focused = true; target.hover = 0; sync(); });
    on(form, 'focusout', (event) => { if (!form.contains(event.relatedTarget)) { focused = false; sync(); } });
    on(document, 'visibilitychange', () => { releasePointer(); if (!document.hidden) resize(); sync(); });
    on(reduced, 'change', () => {
      target.hover = view.hover = 0;
      if (reduced.matches) { releasePointer(); finish(); } else sync();
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
      if (reduced.matches || phase === 'success') finish(); else sync();
    }).catch(() => { fail(); });
    return controller;
  }
  window.YanxuLoginMotion = Object.freeze({ mount });
})();
