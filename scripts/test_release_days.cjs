'use strict';
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict'), crypto = require('node:crypto');
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
const context = {};
vm.runInNewContext(fs.readFileSync('web/releases.js', 'utf8'), context);
vm.runInNewContext(fs.readFileSync('web/updates.js', 'utf8'), context);
const entries = context.YanxuReleases;
const { selectDays, formatRecordTime, latestRecord, summaryCount } = context.YanxuReleaseView;
check(entries.length === 7, 'six recorded days and one month archive');
check(new Set(entries.map(entry => entry.date)).size === entries.length, 'one public entry per date');
check(Object.isFrozen(entries), 'immutable source');
for (const entry of entries) {
  check(Object.isFrozen(entry) && Object.isFrozen(entry.sections), 'immutable day');
  check(/^\d{4}-\d{2}(?:-\d{2})?$/.test(entry.date), 'preserved date precision');
  check(entry.sections.length > 0, 'nonempty daily notes');
  for (const section of entry.sections) {
    check(Object.isFrozen(section) && Object.isFrozen(section.changes), 'immutable section');
    check(['preview', 'published', 'history'].includes(section.status), 'explicit status');
    check(new Set(section.changes).size === section.changes.length, 'no duplicate change within section');
    check(section.changes.length >= 2, 'useful consolidated changes');
    check(Boolean(section.timeSource && section.source), 'auditable time provenance');
    if (section.recordedAt) {
      check(Boolean(formatRecordTime(section.recordedAt)), 'valid exact Beijing record time');
      check(section.recordedAt.slice(0, 10) === entry.date, 'timestamp belongs to day');
    } else {
      check(['document_date', 'document_month'].includes(section.timeSource), 'no fake precision');
    }
  }
}
const all = selectDays(entries), preview = selectDays(entries, 'preview'), published = selectDays(entries, 'published');
check(all.length === 7 && published.length === 7 && preview.length === 1, 'filters preserve consolidated dates');
check(all[0].sections.length === 2, 'same-day preview and published remain distinguishable');
check(preview[0].sections.every(section => section.status === 'preview'), 'preview-only filter');
check(published.every(day => day.sections.every(section => section.status !== 'preview')), 'published filter never leaks preview');
check(entries[0].sections.length === 2, 'filter does not mutate stored history');
check(summaryCount(all) === '6 天更新 · 1 份早期归档', 'count describes days, not iterations');
check(summaryCount(preview) === '1 天更新', 'preview day count');
check(summaryCount([]) === '暂无更新', 'empty count');
check(latestRecord(published[0]) === '2026-09-06T01:53:12+08:00', 'filtered timestamp excludes newer preview');
check(latestRecord(all[0]) === preview[0].sections[0].recordedAt, 'all mode uses latest record of day');
check(latestRecord(entries[1]) === '2026-08-22T02:16:07+08:00', 'August 22 verified merge timestamp');
check(latestRecord(entries[2]) === '2026-08-13T09:31:31+08:00', 'August 13 committer timestamp, not author timestamp');
check(latestRecord(entries[3]) === null && latestRecord(entries[6]) === null, 'early dates never become midnight');
check(latestRecord({ date: '2026-09-06', sections: [{ recordedAt: '2026-09-07T12:00:00+08:00' }] }) === null, 'reject wrong-day records');
check(formatRecordTime('2026-09-06T00:03:04+08:00') === '2026-09-06 00:03:04', '24-hour clock at midnight');
check(formatRecordTime('2026-09-06T23:59:59+08:00') === '2026-09-06 23:59:59', 'explicit Beijing time, second precision');
for (const invalid of [null, '', '2026-08-07', '2026-08', '2026-02-30T01:00:00+08:00', '2026-09-06T24:00:00+08:00', '2026-09-06T01:00:00Z', 'invalid']) {
  check(formatRecordTime(invalid) === null, 'unknown or invalid precision not fabricated');
}
const archive = JSON.parse(fs.readFileSync('docs/release-history-before-daily.json', 'utf8'));
check(archive.length === 21, 'original 21 iterations retained for maintainers');
check(archive[0].id === 'v13-r10', 'archive freezes pre-consolidation state');
check(!archive.some(entry => entry.id === 'v13-r2'), 'no invented iteration');
const renderer = fs.readFileSync('web/updates.js', 'utf8');
check(!/innerHTML|Date\.now|setInterval/.test(renderer), 'safe text rendering and no fake live update time');
check(renderer.includes("element('details', 'release-detail')") && renderer.includes("element('summary', 'release-summary')"), 'native keyboard-operable disclosure');
check(renderer.includes("content.append(detail, stamp)"), 'exact time visible even when detail is closed');
check(renderer.includes('仅留存日期，未记录时分秒') && renderer.includes('早期归档，仅留存月份'), 'honest unknown-time labels');
const asset = fs.readFileSync('web/assets/new-wordmark-v13r11.webp');
check(asset.toString('ascii', 0, 4) === 'RIFF' && asset.toString('ascii', 8, 12) === 'WEBP', 'NEW uses local WebP');
check(asset.length < 10000, 'NEW browser budget under 10KB');
check(crypto.createHash('sha256').update(asset).digest('hex') === '926bd3e5af908c29dd00bc688209184536117475fb691c3556b4716d2127618e', 'reviewed NEW artwork');
const app = fs.readFileSync('web/app.js', 'utf8'), css = fs.readFileSync('web/v13.css', 'utf8');
check(app.includes('class="login-new" src="/assets/new-wordmark-v13r11.webp" alt="NEW" width="160" height="44"'), 'accessible intrinsic-size NEW beside history');
check(css.includes('font-size: 1.0625rem; font-weight: 500;'), 'larger 17px public navigation');
const submit = css.match(/\.orbit-panel \.login-submit \{([^}]+)\}/)?.[1] || '';
check(submit.includes('justify-content: center') && submit.includes('position: relative'), 'submit label centered independently');
check(css.includes('.orbit-panel .login-submit > svg { position: absolute; right: 22px;'), 'arrow does not displace centered label');
for (const page of ['index', 'answer', 'materials', 'updates']) {
  const html = fs.readFileSync('web/' + page + '.html', 'utf8');
  check(html.includes('yanxu-v13-clarity-r11') && html.includes('v13.css?v=20260906v13r11'), 'current shared CSS cache: ' + page);
}
const updateHtml = fs.readFileSync('web/updates.html', 'utf8');
check(updateHtml.includes('releases.js?v=20260906v13r11') && updateHtml.includes('updates.js?v=20260906v13r11'), 'daily schema and renderer cache move together');
console.log(JSON.stringify({ ok: true, suite: 'R11 daily release grouping and exact timestamps', checks }));
