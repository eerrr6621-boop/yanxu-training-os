/* Reuse the preceding homepage when its history entry is verifiable.
 * Direct visits, other origins, modified clicks and older browsers keep normal links. */
(function () {
  'use strict';
  let returning = false;
  window.addEventListener('pageshow', () => { returning = false; });
  for (const link of document.querySelectorAll('a[data-return-home]')) {
    link.addEventListener('click', (event) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey ||
          link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
      if (returning) { event.preventDefault(); return; }
      try {
        const navigation = window.navigation;
        if (!navigation?.entries || !navigation.currentEntry) return;
        const entries = navigation.entries();
        const current = entries.findIndex((entry) => entry.key === navigation.currentEntry.key);
        if (current < 1) return;
        const previous = entries[current - 1];
        // Navigation entries can omit inaccessible origins. Require adjacent indices, too.
        if (previous.index !== navigation.currentEntry.index - 1) return;
        const url = new URL(previous.url), destination = new URL(link.href);
        if (url.origin !== window.location.origin || destination.origin !== url.origin ||
            !['/', '/index.html'].includes(url.pathname) || destination.pathname !== '/' ||
            url.search || url.hash || destination.search || destination.hash) return;
        returning = true;
        window.history.back();
        event.preventDefault();
      } catch (_) { returning = false; /* A normal same-origin home link remains available. */ }
    });
  }
})();
