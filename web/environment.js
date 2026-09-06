/* One disposable clock/weather surface; no browser geolocation, manual city or IP persistence. */
(function (root) {
  'use strict';
  function mount(host, { fetcher = root.fetch.bind(root) } = {}) {
    if (!host) return { destroy() {} };
    const clock = host.querySelector('[data-local-clock]'), date = host.querySelector('[data-local-date]');
    const weather = host.querySelector('[data-local-weather]');
    const login = host.closest('.login-learning');
    let dead = false, clockTimer = 0, refreshTimer = 0, controller = null, loadingRetries = 0, settledAt = 0, refreshDelay = 15 * 60000, failed = false;
    function time() {
      if (dead || document.hidden) return;
      const now = new Date();
      if (clock) { clock.textContent = new Intl.DateTimeFormat('zh-CN', {hour:'2-digit', minute:'2-digit', hourCycle:'h23'}).format(now); clock.dateTime = now.toISOString(); }
      if (date) date.textContent = new Intl.DateTimeFormat('zh-CN', {month:'long', day:'numeric', weekday:'long'}).format(now);
      clockTimer = root.setTimeout(time, 60000 - now.getSeconds() * 1000 - now.getMilliseconds());
    }
    function textNode(tag, className, text) { const el = document.createElement(tag); el.className = className; el.textContent = text; return el; }
    function unavailable(status) {
      weather.replaceChildren(textNode('span', 'weather-unavailable', status === 'not_configured' ? '天气服务待配置' : '暂无法获取天气'));
      weather.title = status === 'location_unavailable' ? '当前网络位置无法可靠识别，不使用服务器城市替代。' : '天气不可用不影响进入工作台。';
    }
    async function load() {
      if (dead || document.hidden || controller || !weather) return;
      controller = new AbortController(); const requestController = controller;
      const timeout = root.setTimeout(() => requestController.abort(), 5500);
      let delay = 15 * 60000;
      try {
        const response = await fetcher('/api/visitor-context', {signal: requestController.signal, credentials:'same-origin', cache:'no-store'});
        if (!response.ok) throw new Error('Unavailable');
        const data = await response.json();
        if (dead) return;
        if (data.status === 'loading' && loadingRetries++ < 6) { delay = 2500; }
        else if (data.status === 'ok' && typeof data.city === 'string' && data.city.length <= 30 && Number.isFinite(data.temperature) && data.temperature >= -90 && data.temperature <= 65 && typeof data.condition === 'string' && data.condition.length <= 24 && Number.isFinite(Date.parse(data.fetchedAt)) && Date.now() - Date.parse(data.fetchedAt) < 3600000 && Date.parse(data.fetchedAt) < Date.now() + 300000 && data.source === 'QWeather') {
          const district = typeof data.district === 'string' && data.district.length <= 30 ? data.district : '';
          weather.replaceChildren(textNode('span', 'weather-location', data.city + (district && district !== data.city ? ' · ' + district : '')), textNode('b', 'weather-temperature', `${Math.round(data.temperature)}°`), textNode('span', 'weather-condition', data.condition));
          const credit = textNode('span', 'weather-source', '');
          const provider = textNode('a', 'weather-provider', '和风天气'); provider.href = 'https://www.qweather.com/'; provider.rel = 'noreferrer';
          provider.title = '和风天气官网';
          credit.append(textNode('span', '', '天气服务由'), provider);
          let attributionCount = 0;
          (Array.isArray(data.attributions) ? data.attributions.slice(0,5) : []).forEach(url => {
            try { const link = new URL(url); if (link.protocol !== 'https:' || link.username || link.password || link.port || url.length > 512) return;
              const note = textNode('a', 'weather-attribution', attributionCount === 0 ? '提供' : ` · 数据来源${attributionCount + 1}`);
              note.href = link.href; note.rel = 'noreferrer'; note.title = '查看天气数据来源';
              note.setAttribute('aria-label', attributionCount === 0 ? '提供 · 天气数据来源说明' : `数据来源${attributionCount + 1} · 天气归因说明`);
              credit.append(note); attributionCount++;
            } catch (_) { /* Invalid provider links are never activated. */ }
          });
          if (!attributionCount) credit.append(textNode('span', '', '提供'));
          weather.append(credit);
          weather.title = `网络位置估算 · 获取于 ${new Date(data.fetchedAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}`;
          loadingRetries = 0; settledAt = Date.now(); failed = false;
        } else { unavailable(data.status); settledAt = Date.now(); loadingRetries = 0; failed = !['not_configured','location_unavailable'].includes(data.status); if (failed) delay = 60000; }
      } catch (_) { if (!dead && !document.hidden) { unavailable('unavailable'); settledAt = Date.now(); failed = true; delay = 60000; } }
      finally {
        root.clearTimeout(timeout); controller = null;
        refreshDelay = delay;
        if (!dead && !document.hidden) refreshTimer = root.setTimeout(load, delay);
      }
    }
    function visibility() {
      root.clearTimeout(clockTimer); root.clearTimeout(refreshTimer);
      if (login) login.dataset.motionPaused = String(document.hidden);
      if (document.hidden) { controller?.abort(); return; }
      time();
      if (Date.now() - settledAt > refreshDelay || (failed && Date.now() - settledAt > 5000)) load();
      else refreshTimer = root.setTimeout(load, Math.max(1000, refreshDelay - (Date.now() - settledAt)));
    }
    function online() { if (failed && !document.hidden && Date.now() - settledAt > 5000) { root.clearTimeout(refreshTimer); load(); } }
    document.addEventListener('visibilitychange', visibility);
    root.addEventListener('online', online);
    time(); load();
    return {destroy() { dead = true; root.clearTimeout(clockTimer); root.clearTimeout(refreshTimer); controller?.abort(); document.removeEventListener('visibilitychange', visibility); root.removeEventListener('online', online); }};
  }
  root.YanxuEnvironment = Object.freeze({mount});
})(typeof window === 'undefined' ? globalThis : window);
