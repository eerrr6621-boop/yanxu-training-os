(function (scope) {
  'use strict';
  const labels = { preview: '本地预览', published: '已发布', history: '历史版本' };
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  });

  // Filter sections, not entire days: a day can contain published AND preview work.
  function selectDays(entries, filter = 'all') {
    return entries.map(entry => ({ ...entry, sections: entry.sections.filter(section =>
      filter === 'all' || (filter === 'published' ? section.status !== 'preview' : section.status === 'preview')
    ) })).filter(entry => entry.sections.length);
  }
  function formatRecordTime(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/.test(value)) return null;
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return null;
    const parts = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
    const result = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
    // Reject normalized impossible dates instead of silently changing their precision.
    return result === value.slice(0, 19).replace('T', ' ') ? result : null;
  }
  function latestRecord(entry) {
    const times = entry.sections.map(section => section.recordedAt)
      .filter(value => formatRecordTime(value) && value.slice(0, 10) === entry.date).sort();
    return times.length ? times[times.length - 1] : null;
  }
  function summaryCount(entries) {
    const days = entries.filter(entry => entry.date.length === 10).length;
    const early = entries.length - days;
    return [days && `${days} 天更新`, early && `${early} 份早期归档`].filter(Boolean).join(' · ') || '暂无更新';
  }
  scope.YanxuReleaseView = Object.freeze({ selectDays, formatRecordTime, latestRecord, summaryCount });

  const document = scope.document;
  const root = document?.getElementById('release-timeline');
  if (!root) return;
  function element(tag, className, text) {
    const node = document.createElement(tag); node.className = className;
    if (text) node.textContent = text;
    return node;
  }
  function render(filter) {
    const entries = selectDays(scope.YanxuReleases || [], filter);
    root.replaceChildren();
    entries.forEach((entry, index) => {
      const article = element('article', 'release-entry'); article.id = entry.id;
      const meta = element('div', 'release-meta');
      const date = element('time', '', entry.date.replaceAll('-', '.')); date.dateTime = entry.date;
      meta.append(date, element('span', 'release-day-label', entry.date.length === 10 ? '当日汇总' : '早期归档'));
      const content = element('div', 'release-content');
      const detail = element('details', 'release-detail'); detail.open = index === 0;
      const summary = element('summary', 'release-summary');
      const heading = element('div', 'release-heading');
      const statuses = element('div', 'release-statuses');
      [...new Set(entry.sections.map(section => section.status))].forEach(status =>
        statuses.append(element('span', 'release-status ' + status, labels[status])));
      heading.append(statuses, element('h2', '', entry.title));
      const arrow = element('i', 'release-chevron'); arrow.setAttribute('data-lucide', 'chevron-down'); arrow.setAttribute('aria-hidden', 'true');
      summary.append(heading, arrow); detail.append(summary);
      entry.sections.forEach(section => {
        const group = element('section', 'release-group');
        const subheading = element('h3', 'release-group-heading');
        subheading.append(element('span', 'release-status ' + section.status, labels[section.status]), element('span', 'release-version', section.version));
        const list = element('ul', 'release-changes');
        [...new Set(section.changes)].forEach(change => list.append(element('li', '', change)));
        group.append(subheading, list); detail.append(group);
      });
      const stamp = element('footer', 'release-record-time');
      const recordedAt = latestRecord(entry);
      if (recordedAt) {
        const time = element('time', '', formatRecordTime(recordedAt)); time.dateTime = recordedAt;
        stamp.append(element('span', '', '记录更新于'), time, element('span', '', '北京时间'));
      } else {
        stamp.textContent = entry.date.length === 10 ? '仅留存日期，未记录时分秒' : '早期归档，仅留存月份';
      }
      content.append(detail, stamp); article.append(meta, content); root.append(article);
    });
    document.getElementById('updates-count').textContent = summaryCount(entries);
    scope.lucide?.createIcons();
  }
  document.querySelectorAll('[data-release-filter]').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('[data-release-filter]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    render(button.dataset.releaseFilter);
  }));
  render('all');
})(typeof window === 'undefined' ? globalThis : window);
