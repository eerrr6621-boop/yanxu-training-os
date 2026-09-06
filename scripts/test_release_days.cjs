'use strict';
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict'), crypto = require('node:crypto');
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
const context = {};
vm.runInNewContext(fs.readFileSync('web/releases.js', 'utf8'), context);
vm.runInNewContext(fs.readFileSync('web/updates.js', 'utf8'), context);
const entries = context.YanxuReleases;
const { selectDays, formatRecordTime, latestRecord } = context.YanxuReleaseView;
check(entries.length >= 7, 'retained recorded days and month archive');
check(new Set(entries.map(entry => entry.date)).size === entries.length, 'one public entry per date');
check(Object.isFrozen(entries), 'immutable source');
for (const entry of entries) {
  check(Object.isFrozen(entry) && Object.isFrozen(entry.sections), 'immutable day');
  check(/^\d{4}-\d{2}(?:-\d{2})?$/.test(entry.date), 'preserved date precision');
  check(entry.sections.length > 0, 'nonempty daily notes');
  for (const section of entry.sections) {
    check(Object.isFrozen(section) && Object.isFrozen(section.changes), 'immutable section');
    check(['published', 'history'].includes(section.status), 'public data contains release history only');
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
const all = selectDays(entries);
check(all.length === entries.length, 'only approved release data is included');
const publicCopy = entries.flatMap(entry => [entry.title, ...entry.sections.flatMap(section => section.changes)]).join('\n');
check(!/ImageGen|Three\.js|WebGL|配色|银白|字形|原生字体|光影|透明品牌|版本回退|浅色导航/.test(publicCopy), 'public notes describe user capabilities, not visual production details');
const mixed = [{date:'2026-09-07',sections:[{status:'preview',recordedAt:'2026-09-07T12:00:00+08:00'}, {status:'published',recordedAt:'2026-09-07T01:00:00+08:00'}, {status:'unknown'}]}];
const publicOnly = selectDays(mixed);
check(publicOnly[0].sections.length === 1 && publicOnly[0].sections[0].status === 'published', 'future draft and unknown states fail closed');
check(mixed[0].sections.length === 3, 'selection never mutates source');
check(selectDays([{sections:[{status:'preview'}]}]).length === 0, 'unreleased-only day never renders');
check(latestRecord(publicOnly[0]) === '2026-09-07T01:00:00+08:00', 'draft timestamp cannot leak into public record');
check(latestRecord(entries.find(x => x.date === '2026-09-06')) === '2026-09-06T22:07:48+08:00', 'actual weather activation recorded in same-day entry');
check(latestRecord(entries.find(x => x.date === '2026-08-22')) === '2026-08-22T02:16:07+08:00', 'August 22 verified merge timestamp');
check(latestRecord(entries.find(x => x.date === '2026-08-13')) === '2026-08-13T09:31:31+08:00', 'August 13 committer timestamp, not author timestamp');
check(latestRecord(entries.find(x => x.date === '2026-08-07')) === null && latestRecord(entries.find(x => x.id === 'initial')) === null, 'early dates never become midnight');
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
check(!/仅留存日期|未记录时分秒|早期归档|当日汇总|updates-count|summaryCount/.test(renderer), 'no maintenance labels or removed count dependency');
function renderNotes(notes) {
  function node(tagName) {
    return { tagName, className: '', children: [], textContent: '',
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = children; },
      setAttribute(name, value) { this[name] = value; } };
  }
  const root = node('section');
  const document = { createElement: node, getElementById(id) {
    check(id === 'release-timeline', 'renderer only requests existing timeline'); return root;
  } };
  let icons = 0;
  vm.runInNewContext(renderer, { YanxuReleases: notes, document, lucide: { createIcons() { icons++; } } });
  check(icons === 1, 'renderer finishes icon refresh');
  return root;
}
const rendered = renderNotes(entries);
check(rendered.children.length === entries.length, 'all actual release days rendered');
for (const [index, entry] of entries.entries()) {
  const [meta, content] = rendered.children[index].children;
  check(meta.children.length === 1 && meta.children[0].tagName === 'time', 'date column has no internal caption');
  check(meta.children[0].dateTime === entry.date && meta.children[0].textContent === entry.date.replaceAll('-', '.'), 'known date precision retained');
  const hasStamp = Boolean(latestRecord(entry));
  check(content.children.length === (hasStamp ? 2 : 1), 'unknown time leaves no empty footer or gap');
  check(content.children[0].tagName === 'details' && content.children[0].open === (index === 0), 'only newest day expanded');
  if (hasStamp) check(content.children[1].tagName === 'footer' && content.children[1].children[1].dateTime === latestRecord(entry), 'exact time is outside collapsible details');
}
check(renderNotes([]).children.length === 0, 'empty history renders without missing-node errors');
check(renderNotes([{ sections: [{ status: 'preview' }] }]).children.length === 0, 'unreleased history never creates DOM');
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
  check(html.includes('yanxu-v13-release-r1') && html.includes('v13.css?v=20260907v13r1-return'), 'current release and shared CSS cache: ' + page);
}
const updateHtml = fs.readFileSync('web/updates.html', 'utf8');
check(updateHtml.includes('releases.js?v=20260907v13r1-return') && updateHtml.includes('updates.js?v=20260907v13r1-notes2'), 'current product notes and stable renderer cache');
check(!/本地预览|已发布|发布与历史|预览迭代|data-release-filter/.test(updateHtml + renderer), 'no internal deployment labels or filters on user-facing page');
check(updateHtml.includes('<h1>更新记录</h1>'), 'plain-language page name');
check(!/updates-toolbar|updates-note|updates-count|同一天的更新|记录来源/.test(updateHtml), 'no public maintenance counters or explanations');
check(css.includes('grid-template-columns: 184px minmax(0,1fr)') && css.includes('font-size: 28px; line-height: 1.25'), 'prominent dates have sufficient column width');
check(css.includes('.release-meta > time { font-size: 24px; min-height: 32px; }'), 'mobile date remains readable');
check(updateHtml.includes('href="mailto:ttttyq0531@qq.com">联系作者：ttttyq0531@qq.com</a>'), 'author address is a mail link inside update records');
check(!app.includes('ttttyq0531@qq.com') && !app.includes('联系作者') && !app.includes('项目已开源'), 'author contact and source link are not on the login homepage');
check(updateHtml.includes('href="https://github.com/eerrr6621-boop/yanxu-training-os" target="_blank" rel="noopener noreferrer">项目已开源'), 'verified public repository link opens safely within update records');
check(css.includes('.project-links { display: flex; flex-wrap: wrap;') && css.includes('overflow-wrap: anywhere'), 'project links wrap on narrow viewports');
console.log(JSON.stringify({ ok: true, suite: 'R11 daily release grouping and exact timestamps', checks }));
