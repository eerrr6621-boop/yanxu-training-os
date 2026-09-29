// Same-origin authentication transport: verification errors never invalidate another session.
// First-binding capabilities and candidate addresses belong only to this mounted page.
const CHALLENGE = /^[a-f0-9]{64}$/;
const ENDPOINTS = new Set(['/login', '/login/email/verify', '/login/email/resend', '/login/email/cancel', '/login/email/bind/send', '/login/email/bind/verify', '/login/email/bind/resend', '/login/email/bind/cancel']);
const instant = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value));
const capability = value => typeof value === 'string' && CHALLENGE.test(value);
const fields = (value, expected) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === expected.length && expected.every(key => Object.hasOwn(value, key));
function validChallenge(value, binding) {
  return fields(value, ['status', 'challenge_id', 'masked_email', 'expires_at', 'resend_after']) && value.status === (binding ? 'EMAIL_BIND_CODE_REQUIRED' : 'EMAIL_REQUIRED') && capability(value.challenge_id) && value.masked_email === '***@***' && instant(value.expires_at) && instant(value.resend_after) && Date.parse(value.resend_after) <= Date.parse(value.expires_at);
}
function validEnrollment(value) {
  return fields(value, ['status', 'enrollment_id', 'expires_at']) && value.status === 'EMAIL_BIND_REQUIRED' && capability(value.enrollment_id) && instant(value.expires_at);
}
function validLogin(value) {
  const user = value?.user;
  return value && !Object.hasOwn(value, 'status') && !Object.hasOwn(value, 'enrollment_id') && !Object.hasOwn(value, 'challenge_id') && typeof value.token === 'string' && value.token.length > 0 && value.token.length <= 512 && user && Number.isSafeInteger(user.uid) && user.uid > 0 && typeof user.username === 'string' && !!user.username && typeof user.role === 'string' && !!user.role && (user.name == null || typeof user.name === 'string');
}
export function mountLoginVerification(form, context = {}) {
  if (!form?.ownerDocument || typeof context.onAuthenticated !== 'function') throw new TypeError('Login host is incomplete');
  const find = selector => form.querySelector(selector);
  const userInput = find('#login-user'), passwordInput = find('#login-pwd'), codeInput = find('#login-code'), bindInput = find('#login-bind-email'), submitButton = find('#login-btn'), errorArea = find('#login-err');
  const credentials = find('#login-password-step'), bindStep = find('#login-bind-step'), emailStep = find('#login-email-step'), emailActions = find('#login-email-actions'), hint = find('#login-email-hint'), timerText = find('#login-code-time'), resendButton = find('#login-resend'), backButton = find('#login-back'), toggle = find('#pwd-toggle'), caps = find('#caps-lock-note');
  if ([userInput,passwordInput,codeInput,bindInput,submitButton,errorArea,credentials,bindStep,emailStep,emailActions,hint,timerText,resendButton,backButton,toggle,caps].some(node => !node)) throw new TypeError('Login fields are incomplete');
  let alive = true, sequence = 0, controller = null, enrollment = null, challenge = null, view = 'credentials', busy = false, pendingKind = null, submittedUsername = '', timer = null;
  const now = context.now || Date.now, setTimer = context.setInterval || globalThis.setInterval, clearTimer = context.clearInterval || globalThis.clearInterval;
  const active = () => alive && form.isConnected && (context.isCurrent?.() ?? true);
  const phase = value => { try { context.onPhase?.(value); } catch { /* Optional visuals do not gate authentication. */ } };
  async function request(path, body, options = {}) {
    if (!ENDPOINTS.has(path)) throw Error('Unsupported authentication path');
    let response;
    try { response = await (context.fetch || globalThis.fetch)('/api' + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error', body: JSON.stringify(body), signal: options.signal, ...(options.keepalive ? { keepalive: true } : {}) }); }
    catch (error) { if (error?.name === 'AbortError') throw error; throw Object.assign(Error('网络连接失败，请恢复连接后重试。'), { status: 0 }); }
    let envelope;
    try { envelope = JSON.parse(await response.text()); } catch { throw Object.assign(Error('登录服务返回异常，请重新登录。'), { status: 502 }); }
    if (!response.ok || envelope?.code !== 0) throw Object.assign(Error('登录请求未完成'), { status: Number(response.status || envelope?.code) });
    return envelope.data;
  }
  function cancelChallenge(id) { if (capability(id)) void request('/login/email/cancel', { challenge_id: id }, { keepalive: true }).catch(() => {}); }
  function cancelEnrollment(id) { if (capability(id)) void request('/login/email/bind/cancel', { enrollment_id: id }, { keepalive: true }).catch(() => {}); }
  function revokeLateLogin(value) {
    // Target only this returned session. Omit cookies so a newer page/tab identity
    // cannot win Api.token precedence; also ignore logout's clearing Set-Cookie.
    void (async () => {
      try { await (context.fetch || globalThis.fetch)('/api/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Token': value.token }, credentials: 'omit', mode: 'same-origin', cache: 'no-store', redirect: 'error', body: '{}', keepalive: true }); } catch { /* Best effort after page exit; never log or retain the token. */ }
    })();
  }
  function cancelReply(value, enrollmentId) {
    if (validLogin(value)) revokeLateLogin(value);
    if (capability(value?.enrollment_id)) cancelEnrollment(value.enrollment_id);
    if (value?.status === 'EMAIL_REQUIRED') cancelChallenge(value.challenge_id);
    if (value?.status === 'EMAIL_BIND_CODE_REQUIRED') cancelEnrollment(enrollmentId);
  }
  function invalidate() { sequence++; controller?.abort(); controller = null; busy = false; pendingKind = null; }
  function stopTimer() { if (timer !== null) clearTimer(timer); timer = null; }
  function startTimer() { stopTimer(); timer = setTimer(updateCountdown, 1000); }
  function updateCountdown() {
    if (!active()) { destroy(); return; }
    if (enrollment && now() >= Date.parse(enrollment.expires_at)) { back('本次邮箱绑定已过期，请重新输入账号和密码。'); return; }
    if (!challenge) { resendButton.disabled = true; return; }
    const expiry = Math.max(0, Math.ceil((Date.parse(challenge.expires_at) - now()) / 1000));
    const resend = Math.max(0, Math.ceil((Date.parse(challenge.resend_after) - now()) / 1000));
    timerText.textContent = expiry ? `验证码有效时间约 ${Math.floor(expiry / 60)} 分 ${expiry % 60} 秒` : '验证码可能已过期，可尝试验证，或返回账号密码重新登录。';
    resendButton.textContent = resend ? `${resend} 秒后可重新发送` : '重新发送'; resendButton.disabled = busy || resend > 0;
  }
  function visible(node, value, display = '') { node.hidden = !value; node.style.display = value ? display : 'none'; }
  function controls() {
    const password = view === 'credentials', binding = view === 'fill-email', code = view === 'code';
    visible(credentials, password); visible(bindStep, binding); visible(emailStep, code); visible(emailActions, !password, 'flex'); visible(resendButton, code);
    userInput.disabled = passwordInput.disabled = busy || !password; userInput.required = passwordInput.required = password;
    toggle.disabled = busy || !password; bindInput.disabled = busy || !binding; bindInput.required = binding; codeInput.disabled = busy || !code; codeInput.required = code;
    submitButton.disabled = busy; const label = busy ? (pendingKind === 'send' || pendingKind === 'resend' ? '正在发送' : '正在验证') : binding ? '发送邮箱验证码' : code ? (enrollment ? '验证邮箱并进入工作台' : '验证并进入工作台') : '进入工作台';
    if (context.setSubmitLabel) context.setSubmitLabel(label, busy); else submitButton.textContent = label;
    if (busy) form.setAttribute('aria-busy', 'true'); else form.removeAttribute('aria-busy');
    backButton.disabled = busy && pendingKind === 'verify'; updateCountdown();
  }
  function clearInputs() {
    passwordInput.value = ''; passwordInput.type = 'password'; context.onPasswordHidden?.(); bindInput.value = ''; codeInput.value = ''; hint.textContent = timerText.textContent = ''; caps.textContent = '';
  }
  function back(message = '') {
    const oldEnrollment = enrollment?.enrollment_id, oldChallenge = enrollment ? null : challenge?.challenge_id;
    invalidate(); enrollment = challenge = null; view = 'credentials'; submittedUsername = ''; stopTimer(); cancelEnrollment(oldEnrollment); cancelChallenge(oldChallenge); clearInputs();
    errorArea.textContent = message; controls(); phase(message ? 'error' : 'idle');
    if (active()) userInput.focus();
  }
  function showEnrollment(value) {
    enrollment = value; challenge = null; view = 'fill-email'; clearInputs(); errorArea.textContent = ''; startTimer(); controls(); phase('idle');
  }
  function showChallenge(value) {
    challenge = value; view = 'code'; clearInputs(); hint.textContent = enrollment ? `首次绑定验证码已发送至 ${value.masked_email}，请查看您刚填写的邮箱。` : `验证码已发送至 ${value.masked_email}，请查看邮箱。`;
    errorArea.textContent = ''; startTimer(); controls(); phase('idle');
  }
  function completed(value) {
    const username = submittedUsername;
    enrollment = challenge = null; submittedUsername = ''; clearInputs(); stopTimer(); phase('success'); context.onAuthenticated(value.user, username);
  }
  function failure(error, kind) {
    const status = Number(error?.status);
    if (enrollment && status === 409) { back('本次邮箱绑定未完成，请重新输入账号和密码。'); return; }
    if ((enrollment || challenge) && [401, 503].includes(status)) { back(status === 401 ? '本次验证已失效，请重新输入账号和密码。' : '邮箱验证服务暂不可用，请稍后重新登录。'); return; }
    if (status === 429) errorArea.textContent = '操作过于频繁，请稍后再试。';
    else if (kind === 'verify' && status === 400) errorArea.textContent = '验证码不正确或格式有误，请重新填写。';
    else if (kind === 'send' && status === 400) errorArea.textContent = '邮箱格式有误，请核对后重新填写。';
    else if (kind === 'login' && status === 409) errorArea.textContent = '账号的个人邮箱尚未准备好，请联系管理员核对。';
    else if (kind === 'login' && status === 401) errorArea.textContent = '账号或密码不正确，或账号已停用，请核对后重试。';
    else if (status === 503) errorArea.textContent = '登录服务暂不可用，请稍后重试。';
    else if (status === 0) errorArea.textContent = kind === 'resend' ? '未能确认重发结果，请稍后重试；原验证码可能已失效。' : kind === 'verify' ? '未能确认验证结果，请重试；若验证已失效，请返回账号密码重新登录。' : '网络连接失败，请恢复连接后重试。';
    else errorArea.textContent = '登录验证未完成，请核对后重试。';
    phase('error');
  }
  async function run(kind) {
    if (!active()) { destroy(); return false; }
    if (busy) return false;
    if (enrollment && now() >= Date.parse(enrollment.expires_at)) { back('本次邮箱绑定已过期，请重新输入账号和密码。'); return false; }
    if (kind === 'resend' && (!challenge || now() < Date.parse(challenge.resend_after))) return false;
    if (kind === 'verify' && !/^[0-9]{6}$/.test(codeInput.value)) { errorArea.textContent = '请输入 6 位数字验证码。'; return false; }
    if (kind === 'login' && (!userInput.value.trim() || !passwordInput.value)) { errorArea.textContent = '请输入账号和密码。'; return false; }
    if (kind === 'send' && (!enrollment || !bindInput.value.trim() || !bindInput.checkValidity())) { errorArea.textContent = '请填写本人可收信的有效邮箱。'; return false; }
    const id = ++sequence; controller?.abort(); controller = new AbortController();
    const bindingId = enrollment?.enrollment_id, previousChallenge = challenge?.challenge_id;
    const body = kind === 'login' ? { username: userInput.value.trim(), password: passwordInput.value } : kind === 'send' ? { enrollment_id: bindingId, email: bindInput.value.trim() } : kind === 'verify' ? { challenge_id: previousChallenge, code: codeInput.value } : { challenge_id: previousChallenge };
    const path = kind === 'login' ? '/login' : '/login/email/' + (bindingId ? 'bind/' : '') + kind;
    if (kind === 'login') submittedUsername = body.username;
    passwordInput.value = ''; if (kind === 'verify') codeInput.value = '';
    busy = true; pendingKind = kind; errorArea.textContent = ''; controls(); phase('loading');
    const current = () => active() && id === sequence;
    try {
      const value = await request(path, body, { signal: controller.signal });
      if (!current()) { cancelReply(value, bindingId); if (!active()) destroy(); return false; }
      if (kind === 'login' && validEnrollment(value)) { showEnrollment(value); return true; }
      if (kind !== 'verify' && validChallenge(value, !!bindingId)) {
        if ((kind === 'resend' && value.challenge_id === previousChallenge) || (bindingId && Date.parse(value.expires_at) > Date.parse(enrollment.expires_at))) { cancelReply(value, bindingId); back('登录服务返回异常，请重新输入账号和密码。'); return false; }
        showChallenge(value); return true;
      }
      if ((kind === 'login' || kind === 'verify') && validLogin(value)) { completed(value); return true; }
      cancelReply(value, bindingId); back('登录服务返回异常，请重新输入账号和密码。'); return false;
    } catch (error) {
      if (current() && error?.name !== 'AbortError') failure(error, kind);
      if (!active()) destroy(); return false;
    } finally { if (current()) { busy = false; pendingKind = null; controls(); if (!errorArea.textContent && view !== 'credentials') (view === 'fill-email' ? bindInput : codeInput).focus(); } }
  }
  function submit() { return run(view === 'code' ? 'verify' : view === 'fill-email' ? 'send' : 'login'); }
  function destroy() {
    if (!alive) return; const oldEnrollment = enrollment?.enrollment_id, oldChallenge = enrollment ? null : challenge?.challenge_id;
    alive = false; invalidate(); stopTimer(); enrollment = challenge = null; view = 'credentials'; submittedUsername = ''; clearInputs(); errorArea.textContent = '';
    visible(credentials, true); visible(bindStep, false); visible(emailStep, false); visible(emailActions, false); visible(resendButton, false);
    userInput.disabled = passwordInput.disabled = toggle.disabled = submitButton.disabled = false; userInput.required = passwordInput.required = true; bindInput.disabled = codeInput.disabled = resendButton.disabled = true; bindInput.required = codeInput.required = false; form.removeAttribute('aria-busy');
    if (context.setSubmitLabel) context.setSubmitLabel('进入工作台', false); else submitButton.textContent = '进入工作台';
    resendButton.onclick = backButton.onclick = null; (context.window || docWindow)?.removeEventListener?.('pagehide', destroy); cancelEnrollment(oldEnrollment); cancelChallenge(oldChallenge); context.onDisposed?.();
  }
  const docWindow = form.ownerDocument.defaultView; (context.window || docWindow)?.addEventListener?.('pagehide', destroy);
  resendButton.onclick = () => run('resend'); backButton.onclick = () => { if (!busy || pendingKind !== 'verify') back(); };
  controls(); return { submit, destroy };
}
