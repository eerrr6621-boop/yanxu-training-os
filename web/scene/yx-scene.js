const ROOT_STATE = 'sceneState';

/**
 * The visual layer is intentionally DOM/CSS-only. The previous WebGL object was
 * decorative, expensive, and unrelated to the training workflow. This controller
 * keeps the small bridge contract used by app.js without creating a canvas.
 */
export function createExperience(host) {
  let destroyed = false;

  const set = (name, value) => {
    if (!host || destroyed) return;
    if (value == null || value === '') delete host.dataset[name];
    else host.dataset[name] = String(value);
  };

  const api = {
    setMode(mode) {
      set('sceneMode', mode);
      set(ROOT_STATE, mode === 'login' ? 'ready' : 'idle');
    },
    setRoute(route) { set('sceneRoute', route); },
    setPhase(phase) { set('scenePhase', phase); },
    resume() { set(ROOT_STATE, 'ready'); },
    pause() { set(ROOT_STATE, 'paused'); },
    destroy() {
      if (!host) return;
      delete host.dataset.sceneMode;
      delete host.dataset.sceneRoute;
      delete host.dataset.scenePhase;
      delete host.dataset[ROOT_STATE];
      host.replaceChildren();
      destroyed = true;
    },
  };

  return api;
}
