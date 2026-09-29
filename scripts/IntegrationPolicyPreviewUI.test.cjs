'use strict';
// Original dispatch page, modal, API and actual M05 modules with synthetic DOM/transport only.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const hostTests = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(hostTests.slice(0, hostTests.indexOf('(async () => {')) + '\n({ harness, detail, dispatch, setModules: value => { modules = value; } });',
  { require, __dirname, structuredClone, AbortController, DOMException, URLSearchParams, URL, console, Buffer, setImmediate, setTimeout });
const dataModule = text => 'data:text/javascript;base64,' + Buffer.from(text).toString('base64');
const read = file => fs.readFileSync(path.join(__dirname, '../web/modules', file), 'utf8');
let modules, checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (a, b, label) => { assert.deepEqual(JSON.parse(JSON.stringify(a)), b, label); checks++; };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function preview(overrides = {}) {
  return { dispatch_id: 7, fact_version: 1, service_date: '2026-09-22', actual_minutes: '60', actual_hours: '1.33', payable_hours: '2.50',
    status: 'CONDITIONAL_PREVIEW', can_confirm: false, amount_scope: 'INDIVIDUAL', raw_amount_is_conditional: true,
    execution_basis: { execution_version: 'SYNTHETIC-POLICY-1', adoption_date: '2026-09-22', adoption_decision: '合成测试执行依据', source_file_name: '合成来源.docx', source_consultation_draft: true, source_publication_date: null, effective_from: null, source_sha256: 'synthetic-only' },
    rate: { unit_rate: '150.001', reference_grade: 'SENIOR', activity: 'TEACHING' }, raw_amount: '375.00250', final_amount: null,
    issues: [{ code: 'SYNTHETIC-CONDITION', message: '合成资格条件待核实' }], formal_missing_items: [],
    scenarios: [{ reference_grade: 'LECTURER', activity: 'TEACHING', teaching_day_type: 'WORKDAY', unit_rate: '100', condition: '合成条件', conditional: true, payable_hours: '2.50', raw_amount: '250.00' }], ...overrides };
}
function harness({ nextPreview, importWait, importFailure, facts = support.detail(), rows, writer = false } = {}) {
  let h;
  h = support.harness({ writer, facts, dispatchRows: rows, fetchOverride: (url, opts, normal, respond) => {
    if (url.startsWith('/api/delivery-settlement/policy-preview?')) {
      if (nextPreview) return nextPreview(url, opts, respond, h);
      return respond(preview({ fact_version: h.facts.version }));
    }
    return normal(url, opts);
  } });
  const originalLoad = h.sandbox.loadHostModule;
  h.policyMounts = [];
  h.sandbox.loadHostModule = async url => {
    if (!url.includes('/policy-preview.js')) return originalLoad(url);
    h.imports.push(url); if (importWait) await importWait.promise; if (importFailure) throw Error('synthetic module failure');
    return { mountPolicyPreview(root, context) { const instance = modules.policy.mountPolicyPreview(root, context); h.policyMounts.push({ root, context, instance }); return instance; } };
  };
  h.reads = () => h.calls.filter(c => c.url.startsWith('/api/delivery-settlement/policy-preview?'));
  h.policy = () => h.modal()?.querySelector('[data-m05-policy-preview]');
  h.slot = key => h.modal()?.querySelector(`[data-m05-policy-slot="${key}"]`);
  h.refresh = () => h.emit(h.modal().querySelector('[data-m05-policy-action="refresh"]'), 'click');
  return h;
}
(async () => {
  modules = {
    catalog: await import(dataModule(read('course-catalog/index.js'))),
    delivery: await import(dataModule(read('delivery-settlement/index.js').replace("'../settlement/conversion.js'", JSON.stringify(dataModule(read('settlement/conversion.js')))))),
    policy: await import(dataModule(read('delivery-settlement/policy-preview.js'))),
  };
  support.setModules(modules);
  const h = harness(); await h.mount();
  check(!h.reads().length && !h.imports.length, 'Policy preview is lazy until original course action opens');
  await h.act(7, '授课记录'); const mask = h.modal(), evidence = h.field('evidence_code');
  check(h.policy() && h.modal().querySelector('[data-m05-delivery]'), 'Actual policy component mounts inside existing delivery modal');
  check(h.policyMounts[0].context.api === h.sandbox.api, 'Policy uses original session-aware API without replacing transport');
  check(h.imports.includes('/modules/delivery-settlement/policy-preview.js?v=20260923policycombined1'), 'Policy uses versioned local lazy resource');
  equal(h.reads()[0].url, '/api/delivery-settlement/policy-preview?dispatch_id=7', 'Read-only request contains only course identifier');
  check(h.reads().every(c => c.opts.method === 'GET' && !c.opts.body && c.opts.signal && c.opts.credentials === 'same-origin'), 'Policy request is authenticated cancellable GET without client facts');
  check(h.policy().textContent.includes('资格或业务条件待核') && h.policy().textContent.includes('仅在相关条件成立时适用'), 'Conditional server preview remains visibly conditional');
  equal(h.slot('raw').textContent, '375.00250 元', 'Original host preserves full unrounded server precision');
  equal(h.slot('final').textContent, '待确定', 'Missing final amount is never inferred from preview');
  check(h.policy().textContent.includes('60 分钟') && h.policy().textContent.includes('1.33 课时') && h.policy().textContent.includes('2.50 课时'), 'Actual minutes, actual hours and payable hours remain separate server values');
  equal(h.policy().querySelectorAll('button').map(b => b.textContent), ['读取最新预览'], 'Policy offers no formal confirmation or payment mutation');
  check(h.modal().textContent.includes('未保存修改不参与'), 'Host explains that preview reads saved facts only');
  await h.edit('evidence_code', 'KEEP-BASIS'); await h.edit('actual_minutes', '60'); await h.click('save');
  check(h.reads().length === 2 && h.modal() === mask && h.field('evidence_code') === evidence && evidence.value === 'KEEP-BASIS', 'Saved facts refresh preview without rebuilding modal or losing evidence');
  await h.click('verify');
  check(h.reads().length === 3 && evidence.value === 'KEEP-BASIS', 'Verification refreshes preview and preserves input');
  await h.click('complete'); await tick();
  check(h.reads().length === 4 && h.renders === 0 && h.modal() === mask, 'Completion keeps original modal and refreshes preview without whole-page rendering');
  h.sandbox.closeModal();
  check(!h.modal() && h.policyMounts[0].context.signal.aborted, 'Original close ends preview scope as well as delivery scope');
  check(h.reads().at(-1).opts.signal.aborted, 'Original close aborts owned preview request controller');

  let response = preview(); const refresh = harness({ nextPreview: (_url, _opts, respond) => typeof response === 'function' ? response(respond) : respond(response) });
  await refresh.mount(); await refresh.act(7, '授课记录'); const waiting = deferred(); response = respond => waiting.promise.then(respond);
  const pendingRefresh = refresh.refresh(); await tick();
  check(!refresh.slot('raw') && !refresh.slot('content').textContent.includes('375.00250'), 'Manual refresh clears old money before new GET settles');
  waiting.resolve(preview({ status: 'HISTORY_PROTECTED', raw_amount: '9999.99', final_amount: '9999.99', rate: { unit_rate: '9999' } })); await pendingRefresh;
  check(refresh.policy().textContent.includes('历史记录受保护') && !refresh.policy().textContent.includes('9999'), 'History-protected response suppresses new rate, amount and scenarios');
  check(!refresh.slot('scenarios') && !refresh.policy().textContent.includes('250.00'), 'Historical protection removes prior condition scenarios');
  response = () => Promise.reject(Error('synthetic offline')); await refresh.refresh();
  check(!refresh.slot('raw') && !refresh.slot('content').textContent && refresh.policy().textContent.includes('暂时无法读取'), 'Failed preview GET leaves no stale history or amount');
  check(!refresh.modal().querySelector('[data-m05-policy-action="refresh"]').disabled, 'Network failure allows explicit reread only');
  response = preview({ status: 'NOT_ELIGIBLE', raw_amount: '555.00' }); await refresh.refresh();
  check(!refresh.policy().textContent.includes('555.00') && !refresh.policy().textContent.includes('250.00'), 'Noneligible result never exposes main or scenario amounts');
  response = preview({ amount_scope: 'DEVELOPMENT_POOL', rate: { unit_rate: '100', activity: 'JOINT_DEVELOPMENT' }, raw_amount: '900.00', pool_raw_amount: '1200.000', individual_raw_amount: null }); await refresh.refresh();
  check(refresh.slot('pool-raw').textContent === '1200.000 元' && refresh.slot('individual-raw').textContent === '待核', 'Joint-development pool and individual share remain separate');
  check(!refresh.policy().textContent.includes('900.00'), 'Generic raw value cannot become a personal allocation');
  response = preview({ execution_basis: { adoption_decision: '<img onerror=alert(1)>', source_file_name: '<script>bad</script>' } }); await refresh.refresh();
  check(!refresh.policy().querySelector('img') && !refresh.policy().querySelector('script') && refresh.policy().textContent.includes('<img'), 'Original host retains module text-only source rendering');
  refresh.leave(); check(!refresh.modal(), 'Route cleanup closes owned original modal');

  for (const status of [401, 403, 409, 500]) {
    let statusNow = 200; const failure = harness({ nextPreview: (_url, _opts, respond) => respond(preview(), statusNow) }); await failure.mount(); await failure.act(7, '授课记录'); statusNow = status; await failure.refresh();
    if (status === 401) check(failure.invalidations === 1 && !failure.modal(), 'Preview 401 triggers original logout and clears both components');
    else {
      check(!failure.slot('content').textContent && failure.field('actual_minutes'), `${status}: failed policy read clears preview but preserves delivery form`);
      equal(failure.modal().querySelector('[data-m05-policy-action="refresh"]').disabled, status === 403, `${status}: retry follows module authorization rule`);
    }
    check(!failure.posts().length, `${status}: preview failure never causes mutation`); failure.leave();
  }
  const lateResponse = deferred(); const late = harness({ nextPreview: (_url, _opts, respond) => lateResponse.promise.then(respond) }); await late.mount(); const opening = late.act(7, '授课记录'); await tick();
  const successor = late.sandbox.openModal('后续弹窗', '<p>保留</p>', { noFoot: true }); lateResponse.resolve(preview()); await opening;
  check(late.modal() === successor && !successor.querySelector('[data-m05-policy-preview]'), 'Closed preview late success cannot modify or close successor modal');
  check(late.reads()[0].opts.signal.aborted, 'Replacing original modal aborts pending preview GET');
  const importWait = deferred(); const importing = harness({ importWait }); await importing.mount(); const importOpening = importing.act(7, '授课记录'); await tick(); importing.leave(); importWait.resolve(); await importOpening;
  check(!importing.policyMounts.length && !importing.reads().length && importing.content.textContent === '后续页面', 'Late optional import cannot mount or fetch after route departure');
  const broken = harness({ importFailure: true }); await broken.mount(); await broken.act(7, '授课记录');
  check(broken.modal().textContent.includes('课酬预览暂时无法加载') && broken.field('actual_minutes') && !broken.reads().length, 'Optional preview load failure preserves original authorized delivery flow');
  check(/<script src="\/app\.js\?v=\d{8}[a-z0-9]+"><\/script>/.test(fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8')), 'Original app keeps an explicitly versioned script for the integration release');
  console.log(JSON.stringify({ ok: true, suite: 'original policy preview host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
