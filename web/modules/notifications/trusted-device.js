// Startup only. Cookies stay browser-managed; this module never retains a response token.
const RESTORE_LOCK = 'yanxu-login-device-restore-v1';
const REQUEST_TIMEOUT_MS = 8000; // Includes response-body parsing.
const LOCK_WAIT_TIMEOUT_MS = 12000;
const STOP = Symbol('stopped');

function userFrom(value) {
  if (!value || Array.isArray(value) || !Number.isSafeInteger(value.uid) || value.uid <= 0 ||
      typeof value.username !== 'string' || !value.username.trim() || typeof value.name !== 'string' ||
      !['admin', 'manager', 'viewer'].includes(value.role) || typeof value.roleName !== 'string') return null;
  // Return only the existing UI user contract, including when a malformed server adds secrets.
  return { uid: value.uid, username: value.username, name: value.name, role: value.role, roleName: value.roleName };
}

export async function restoreInitialSession({ fetch = globalThis.fetch, locks = globalThis.navigator?.locks, signal } = {}) {
  if (typeof fetch !== 'function' || signal?.aborted) return null;
  const controller = new AbortController();
  let stopped = false, abortWait;
  const cancelled = new Promise(resolve => { abortWait = resolve; });
  const stop = () => {
    if (stopped) return;
    stopped = true;
    controller.abort();
    abortWait(STOP);
  };
  signal?.addEventListener('abort', stop, { once: true });
  if (signal?.aborted) stop();

  async function request(url, method = 'GET') {
    if (stopped) return STOP;
    const timer = setTimeout(stop, REQUEST_TIMEOUT_MS);
    try {
      const response = await Promise.race([
        Promise.resolve().then(() => stopped ? STOP : fetch(url, {
          method, credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error',
          headers: { Accept: 'application/json', ...(method === 'POST' ? { 'Content-Type': 'application/json' } : {}) },
          ...(method === 'POST' ? { body: '{}' } : {}), signal: controller.signal,
        })),
        cancelled,
      ]);
      if (stopped || response === STOP) return STOP;
      if (response?.status === 401) return { status: 401 };
      if (response?.status !== 200) return STOP;
      const envelope = await Promise.race([response.json(), cancelled]);
      if (stopped || !envelope || envelope.code !== 0) return STOP;
      return { status: 200, data: envelope.data };
    } catch {
      return STOP;
    } finally {
      clearTimeout(timer);
    }
  }

  async function restore() {
    if (stopped) return null;
    const response = await request('/api/login/device/restore', 'POST');
    return !stopped && response !== STOP && response.status === 200 ? userFrom(response.data?.user) : null;
  }

  try {
    const initial = await request('/api/me');
    if (stopped || initial === STOP) return null;
    if (initial.status === 200) return userFrom(initial.data);
    // Only an explicit HTTP 401 can reach here; network/service/DTO failures use ordinary login.
    if (!locks || typeof locks.request !== 'function') return await restore();
    const timer = setTimeout(stop, LOCK_WAIT_TIMEOUT_MS);
    try {
      const result = await Promise.race([
        Promise.resolve().then(() => stopped ? null : locks.request(RESTORE_LOCK, { mode: 'exclusive', signal: controller.signal }, async lock => {
          clearTimeout(timer);
          if (stopped || !lock) return null;
          // A preceding tab may already have rotated the device cookie and opened a short session.
          const current = await request('/api/me');
          if (stopped || current === STOP) return null;
          return current.status === 200 ? userFrom(current.data) : await restore();
        })),
        cancelled,
      ]);
      return !stopped && result !== STOP ? result : null;
    } finally {
      clearTimeout(timer);
    }
  } catch {
    return null;
  } finally {
    signal?.removeEventListener('abort', stop);
    stop();
  }
}
