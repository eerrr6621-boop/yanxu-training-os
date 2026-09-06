(function () {
  'use strict';
  const root = document.getElementById('release-timeline');
  const labels = { preview: '本地预览', published: '已发布', history: '历史版本' };
  function element(tag, className, text) { const node = document.createElement(tag); node.className = className; if (text) node.textContent = text; return node; }
  function render(filter) {
    const entries = (window.YanxuReleases || []).filter(item => filter === 'all' || (filter === 'published' ? item.status !== 'preview' : item.status === 'preview'));
    root.replaceChildren();
    entries.forEach((entry, index) => {
      const article = element('article', 'release-entry'); article.id = entry.id;
      const meta = element('div', 'release-meta');
      const time = element('time', '', entry.date.replaceAll('-', '.')); time.dateTime = entry.date;
      meta.append(time, element('span', 'release-status ' + entry.status, labels[entry.status]));
      const detail = element('details', 'release-detail'); detail.open = index === 0;
      const summary = element('summary', 'release-summary');
      const heading = element('div', ''); heading.append(element('span', 'release-version', entry.version), element('h2', '', entry.title));
      const arrow = element('i', 'release-chevron'); arrow.setAttribute('data-lucide','chevron-down'); arrow.setAttribute('aria-hidden','true'); summary.append(heading, arrow);
      const list = element('ul', 'release-changes'); entry.changes.forEach(change => list.append(element('li', '', change)));
      detail.append(summary, list); article.append(meta, detail); root.append(article);
    });
    document.getElementById('updates-count').textContent = `${entries.length} 次更新`;
    window.lucide?.createIcons();
  }
  document.querySelectorAll('[data-release-filter]').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('[data-release-filter]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    render(button.dataset.releaseFilter);
  }));
  render('all');
})();
