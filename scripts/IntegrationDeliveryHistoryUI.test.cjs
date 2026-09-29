'use strict';
// Actual original dispatch page/modal/forms/API + actual delivery component. Synthetic DOM/transport only.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const suite = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(suite.slice(0, suite.indexOf('(async () => {')) + '\n({ harness, detail, setModules(value) { modules = value; } });', { require, __dirname, console, structuredClone, AbortController, DOMException, URLSearchParams, URL, setImmediate });
const moduleUrl = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
const read = file => fs.readFileSync(path.join(__dirname, '../web/modules', file), 'utf8');
const tick = () => new Promise(yes => setImmediate(yes));
const defer = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
function check(value, label) { assert.ok(value, label); checks++; }
function equal(actual, expected, label) { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; }
function detail(version = 23, permitted = true) { const value = support.detail(permitted, permitted); value.version = value.capabilities.fact_version = version; return value; }
function history(current = 23, page = 1, overrides = {}) {
  const total = current, first = current - (page - 1) * 20;
  return { dispatch_id: 7, project_id: 5, teacher_id: 8, organization_code: 'ORG-SYNTH', current_version: current, page, page_size: 20, total,
    items: Array.from({ length: Math.max(0, Math.min(20, first)) }, (_, index) => ({ version: first - index, event_type: index === 1 ? 'VERIFY' : 'SAVE', actor_code: 'SYNTHETIC-ACTOR', account_id: 11, created_at: '2026-09-24T01:02:03Z', estimated_hours: '0', planned_hours: null, actual_minutes: '60', actual_hours: '1.33', payable_hours: '9007199254740993.12345678', verification: index === 1 ? { actor_code: 'SYNTHETIC-ACTOR', checked_at: '2026-09-24T01:02:03Z', evidence_code: 'EVIDENCE-1' } : null, changes: [{ field: 'estimated_hours', before: null, after: '0' }, { field: 'planned_hours', before: '2.00', after: null }, { field: 'payable_hours', before: null, after: '9007199254740993.12345678' }], verification_invalidated: index === 0 })), ...overrides };
}
function harness({ version = 23, readOnly = false, override } = {}) {
  const h = support.harness({ facts: detail(version, !readOnly), fetchOverride: (url, opts, normal, respond) => {
    const urlObject = new URL(url, 'http://synthetic.test');
    const fallback = () => urlObject.pathname === '/api/delivery-settlement/history' ? respond(history(h.facts.version, Number(urlObject.searchParams.get('page')))) : normal(url, opts);
    return override ? override(url, opts, fallback, respond, h) : fallback();
  } });
  h.open = async () => { await h.mount(); await h.act(7, '授课记录'); };
  h.historyRoot = () => h.modal()?.querySelector('[data-m05-history]');
  h.historyButton = action => h.historyRoot()?.querySelector('[data-m05-history-action="' + action + '"]');
  h.historyClick = async (action, version) => { const button = version ? h.historyRoot().querySelector('[data-version="' + version + '"]') : h.historyButton(action); check(button && !button.disabled, action + ': explicit history action enabled'); await h.emit(button, 'click'); };
  h.historyCalls = () => h.calls.filter(call => call.url.startsWith('/api/delivery-settlement/history?'));
  return h;
}
(async () => {
  support.setModules({ catalog: {}, delivery: await import(moduleUrl(read('delivery-settlement/index.js').replace("'../settlement/conversion.js'", JSON.stringify(moduleUrl(read('settlement/conversion.js')))))) });
  const h = harness(); await h.open(); const originalMask = h.modal();
  equal(h.historyCalls().length, 0, 'Opening original form does not eagerly load audit data');
  await h.edit('actual_minutes', '67.5'); await h.historyClick('toggle');
  equal(h.historyCalls()[0].url, '/api/delivery-settlement/history?dispatch_id=7&expected_version=23&page=1&page_size=20', 'History binds exact current target and fact version');
  check(h.modal() === originalMask && h.field('actual_minutes').value === '67.5' && h.field('actual_hours').value === '1.50', 'Read-only history preserves original modal and dirty input');
  const comparison = () => h.historyRoot().querySelector('[data-m05-history-comparison]').textContent;
  check(comparison().includes('预计 0') && comparison().includes('计划 未记录') && comparison().includes('9007199254740993.12345678'), 'Exact decimals, zero and null remain distinct');
  check(comparison().includes('本次保存使原核对失效'), 'Server-provided invalidation is explicitly identified');
  equal(h.historyRoot().querySelectorAll('table.tbl').length, 2, 'Timeline and comparison reuse original responsive table class');
  check(h.historyRoot().textContent.includes('系统人员标识 SYNTHETIC-ACTOR · 账号 ID 11'), 'Audit identity is labeled as system identifiers rather than a current name or settlement code');
  check(h.historyRoot().querySelector('th').getAttribute('scope') === 'col' && h.historyRoot().querySelector('td').getAttribute('data-label') === '修订版本', 'Original mobile table labels and column semantics are present');
  await h.historyClick('select', 22); check(comparison().includes('核对前后对照') && !comparison().includes('本次保存使原核对失效'), 'Selecting revision only changes read-only comparison');
  await h.historyClick('next'); check(comparison().includes('版本 3') && comparison().includes('上一版本'), 'Page two uses its server-supplied predecessor comparison');
  check(h.historyButton('next').disabled && !h.historyButton('prev').disabled, 'Pagination respects server total');
  await h.historyClick('prev'); equal(h.field('actual_minutes').value, '67.5', 'Pagination never rewrites user draft');
  equal(h.posts().length, 0, 'Opening, selecting and paging history never POST');
  await h.historyClick('toggle'); check(!h.historyRoot().querySelector('[data-m05-history-panel]'), 'Collapsing removes private history DOM');
  await h.historyClick('toggle'); await h.click('save');
  check(!h.historyRoot().querySelector('[data-m05-history-panel]'), 'Successful save removes prior version history and collapses');
  await h.historyClick('toggle'); check(h.historyCalls().at(-1).url.includes('expected_version=24'), 'Explicit reopening reads saved new version');
  await h.edit('evidence_code', 'SYNTHETIC-EV'); await h.click('verify'); check(!h.historyRoot().querySelector('[data-m05-history-panel]'), 'Successful verification also removes old history');
  const empty = harness({ version: 0 }); await empty.open(); await empty.historyClick('toggle'); check(empty.historyRoot().textContent.includes('尚无保存或核对记录') && !empty.historyRoot().querySelector('table'), 'Unwritten detail has an honest empty history');
  const reader = harness({ readOnly: true }); await reader.open(); await reader.historyClick('toggle'); check(reader.historyCalls().length === 1 && reader.field('actual_minutes').disabled, 'Existing read access is sufficient without write/verify permissions');
  let outdated = true;
  const conflict = harness({ override: (url, opts, normal, respond) => url.startsWith('/api/delivery-settlement/history?') && outdated ? respond(null, 409) : normal() }); await conflict.open(); await conflict.edit('actual_minutes', '91'); await conflict.historyClick('toggle');
  check(!conflict.historyRoot().querySelector('table') && conflict.historyRoot().textContent.includes('读取最新记录'), '409 clears old history and requires explicit detail refresh');
  await conflict.historyClick('toggle'); check(conflict.historyButton('toggle').disabled && conflict.button('save').disabled, 'Conflict requires explicit detail refresh before reopening or writing'); equal(conflict.historyCalls().length, 1, 'Collapsing cannot silently bypass conflict');
  outdated = false; conflict.facts.version = conflict.facts.capabilities.fact_version = 24; await conflict.click('refresh'); await conflict.historyClick('toggle'); equal(conflict.field('actual_minutes').value, '91', 'Explicit latest-detail refresh retains unsaved input'); check(conflict.historyCalls().at(-1).url.includes('expected_version=24'), 'Freshly read version is used only after explicit refresh');
  for (const status of [401, 403, 409, 500]) {
    let fail = false; const rejected = harness({ override: (url, opts, normal, respond) => fail && url.startsWith('/api/delivery-settlement/history?') ? respond(null, status) : normal() }); await rejected.open(); await rejected.historyClick('toggle'); fail = true; await rejected.historyClick('next');
    check(!rejected.historyRoot()?.querySelector('table') && !rejected.modal()?.textContent.includes('9007199254740993.12345678'), status + ': failed page request clears all prior private history');
    if (status === 403) check(rejected.field('actual_minutes').disabled, 'History permission denial removes stale action capabilities');
  }
  for (const change of [value => { value.dispatch_id = 99; }, value => { value.current_version = 99; }, value => { value.project_id = 99; }, value => { value.items[0].changes[0].field = 'amount'; }, value => { value.items[0].actual_hours = 1.33; }]) {
    const invalid = history(); change(invalid); const rejected = harness({ override: (url, opts, normal, respond) => url.startsWith('/api/delivery-settlement/history?') ? respond(invalid) : normal() }); await rejected.open(); await rejected.historyClick('toggle'); check(!rejected.historyRoot().querySelector('table') && rejected.historyRoot().textContent.includes('暂时无法读取'), 'Malformed/wrong target/unknown-field response fails closed');
  }
  const evilValue = '<img src=x onerror=alert(1)>', malicious = history(); malicious.items[0].actor_code = evilValue; malicious.items[0].created_at = evilValue; malicious.items[0].changes = [{ field: 'verification_evidence_code', before: null, after: evilValue }];
  const escaped = harness({ override: (url, opts, normal, respond) => url.startsWith('/api/delivery-settlement/history?') ? respond(malicious) : normal() }); await escaped.open(); await escaped.historyClick('toggle'); check(escaped.historyRoot().textContent.includes(evilValue) && !escaped.historyRoot().querySelector('img,script'), 'Actor, time and evidence remain escaped text');
  const noChanges = history(); noChanges.items[0].changes = []; const same = harness({ override: (url, opts, normal, respond) => url.startsWith('/api/delivery-settlement/history?') ? respond(noChanges) : normal() }); await same.open(); await same.historyClick('toggle'); check(same.historyRoot().textContent.includes('本次修订的课时与核对内容无变化') && same.historyRoot().textContent.includes('原核对失效'), 'Same-value SAVE remains a revision and may invalidate verification');
  const gate = defer(), slow = harness({ override: (url, opts, normal, respond) => url.startsWith('/api/delivery-settlement/history?') ? gate.promise.then(respond) : normal() }); await slow.open(); const opening = slow.historyClick('toggle'); await tick();
  await slow.historyClick('toggle'); check(slow.historyCalls()[0].opts.signal.aborted, 'Collapse aborts in-flight history GET'); gate.resolve(history()); await opening; check(!slow.historyRoot().querySelector('[data-m05-history-panel]'), 'Late collapsed response cannot repopulate history');
  const pageGate = defer(), paging = harness({ override: (url, opts, normal, respond) => url.includes('/history?') && url.includes('page=2') ? pageGate.promise.then(respond) : normal() }); await paging.open(); await paging.historyClick('toggle'); const next = paging.historyClick('next'); await tick();
  check(!paging.historyButton('next'), 'Pending page clears obsolete timeline and prevents duplicate navigation'); equal(paging.historyCalls().length, 2, 'Only one request per explicit page navigation'); paging.leave(); pageGate.resolve(history(23, 2)); await next; check(!paging.modal() && paging.historyCalls()[1].opts.signal.aborted, 'Route cleanup aborts late page and closes owned modal');
  const owner = harness(); await owner.open(); await owner.historyClick('toggle'); const context = owner.mounts[0]; owner.sandbox.closeModal(); owner.sandbox.openModal('其他窗口', '<p>保留</p>', { noFoot: true }); owner.leave(); check(owner.modal()?.textContent.includes('其他窗口'), 'Cleanup never repopulates or closes a successor through obsolete ownership');
  console.log(JSON.stringify({ ok: true, suite: 'original delivery history UI', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
