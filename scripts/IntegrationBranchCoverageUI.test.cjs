'use strict';
// Actual users page, importer, original table/modal/API. Synthetic DOM and transport only.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const base = fs.readFileSync(path.join(__dirname, 'IntegrationAccountImportUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(base.slice(0, base.indexOf('\n(async () => {')) + '\n({ harness, fixtures });', { require, __dirname, console, structuredClone, AbortController, setImmediate });
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (value, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(value)), expected, label); checks++; };
const tick = () => new Promise(yes => setImmediate(yes));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
function fixture(imported = false) {
  const p = support.fixtures.fixture();
  p.rows[0].name = p.rows[1].name = 'SYNTHETIC 同名人员';
  p.rows.push({ ...structuredClone(p.rows[1]), reference: 'leader-extra', name: '<img src=x onerror=alert(1)> SYNTHETIC 牵头', organization: '合成机构甲', approvalRoles: [{ kind: 'BRANCH_LEAD', proposedBranches: ['合成机构甲'], proposedRegions: [], canApprove: false }] });
  p.rows.forEach(row => { row.alreadyImported = false; row.personCode = row.accountId = null; row.combinedDutiesRequiresWorkflowReview = row.reference === 'teacher-row-5'; }); p.summary.candidates = p.rows.length;
  const candidate = (reference, roleKinds) => ({ reference, roleKinds, accountId: null, accountState: 'NOT_RECEIVED' });
  p.branchCoverage = [
    { branch: '合成机构甲', region: '合成区域', leaderCandidates: [candidate('teacher-row-5', ['BRANCH_RESPONSIBLE']), candidate('leader-extra', ['BRANCH_LEAD'])], bpCandidates: [candidate('teacher-row-5', ['BP'])], combinedCandidates: ['teacher-row-5'], routingStatus: 'MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING', defaultHandlerReference: null, canApprove: false, readOnly: true, preparationOnly: true },
    { branch: '合成机构乙', region: '合成区域', leaderCandidates: [candidate('teacher-row-4', ['BRANCH_RESPONSIBLE'])], bpCandidates: [candidate('teacher-row-5', ['BP'])], combinedCandidates: [], routingStatus: 'ROLE_EVIDENCE_PREPARED', defaultHandlerReference: null, canApprove: false, readOnly: true, preparationOnly: true },
  ];
  if (imported) {
    p.imported = true; p.receipt = support.fixtures.makeReceipt(p, p.rows.map(row => ({ reference: row.reference, action: 'CREATE_PENDING' })));
    for (const row of p.rows) Object.assign(row, p.receipt.rows.find(item => item.reference === row.reference), { alreadyImported: true });
    for (const branch of p.branchCoverage) for (const candidate of [...branch.leaderCandidates, ...branch.bpCandidates]) { candidate.accountId = p.rows.find(row => row.reference === candidate.reference).accountId; candidate.accountState = candidate.reference === 'teacher-row-4' ? 'RECEIVED_ENABLED' : 'RECEIVED_DISABLED'; }
  }
  return p;
}
async function mount(options = {}) { const h = support.harness({ preview: fixture(), ...options }); await h.mount(); await h.openImport(); h.node = key => h.importRoot().querySelector('[data-import-' + key + ']'); h.switch = mode => h.node('view-' + mode).onclick(); h.text = () => h.node('branch-table').textContent; return h; }
(async () => {
  const h = await mount(); await h.review(0); const originalProgress = h.node('progress').textContent;
  h.node('search').value = '同名'; h.node('search').oninput({ target: h.node('search') }); await h.switch('branches');
  check(h.node('person-content').hidden && !h.node('branches').hidden, 'Original import card switches to read-only branch view');
  check(h.node('person-filters').style.display === 'none' && h.node('review-info').style.display === 'none', 'Scoped display handles original toolbar styles');
  check(h.node('commit').disabled && !h.node('branch-table').querySelector('button,input,select'), 'Branch view cannot import, publish, activate or choose a handler');
  equal(h.previewRequests().length, 1, 'Switch reuses one verified preview without extra requests'); equal(h.importPosts().length, 0, 'Branch reads make no POST');
  const branchRows = h.node('branch-table').querySelector('tbody').querySelectorAll('tr'); equal(branchRows.length, 2, 'Both branches preserve trusted order');
  equal(branchRows[0].querySelectorAll('td')[1].children.length, 1, 'Multiple leaders share one responsive cell container');
  check(branchRows[0].textContent.includes('合成人员丙') && branchRows[0].textContent.includes('SYNTHETIC 牵头') && branchRows[0].textContent.includes('多人均保留'), 'All leaders and lead roles remain displayed without selection');
  check(branchRows[0].textContent.includes('明确兼任准备') && branchRows[0].textContent.includes('两项职责') && branchRows[0].textContent.includes('未指定默认办理人'), 'Combined preparation retains two responsibilities and no default');
  check(branchRows[1].textContent.includes('SYNTHETIC 同名人员') && !branchRows[1].textContent.includes('无审批岗位'), 'Same-name ordinary teacher is not mapped into a leader role');
  check(h.text().includes('待接收') && !h.text().includes('账号 ID'), 'Unimported people have no fabricated account');
  check(!h.node('branch-table').querySelector('img,script') && h.text().includes('<img'), 'Names remain escaped text');
  check(!h.text().includes('PRIVATE') && !h.text().includes('teacher-row') && !h.text().includes('sourceFingerprint'), 'Private proof and source references are not displayed');
  h.node('branch-search').value = '牵头'; h.node('branch-search').oninput({ target: h.node('branch-search') }); equal(h.node('branch-table').querySelector('tbody').querySelectorAll('tr').length, 1, 'Search filters branches by declared personnel');
  await h.switch('people'); check(h.node('person-filters').style.display === '', 'Switching back restores original toolbar styles'); equal(h.node('search').value, '同名', 'Original personnel search survives switching'); equal(h.node('progress').textContent, originalProgress, 'Switch never changes individual review progress');
  await h.switch('branches'); h.node('commit').onclick(); check(!h.modal(), 'Even a stale commit callback cannot write from branch mode');
  h.node('branch-search').value = '不存在'; h.node('branch-search').oninput({ target: h.node('branch-search') }); check(h.text().includes('未找到匹配'), 'No-match state is explicit');
  const received = await mount({ preview: fixture(true) }); await received.switch('branches');
  check(received.text().includes('已接收 · 账号停用') && received.text().includes('已接收 · 账号启用') && received.text().includes('账号 ID 202') && !received.text().includes('PERSON-002'), 'Receipt account IDs and states appear without unnecessary internal person codes');
  check(received.node('branches').textContent.includes('不代表账号已启用或已获审批权限'), 'Enabled accounts are not described as approval authority');
  equal(received.importPosts().length, 0, 'Existing receipt view never imports again');
  const invalid = [p => { p.branchCoverage[0].readOnly = false; }, p => { delete p.branchCoverage[0].preparationOnly; }, p => { p.branchCoverage[0].leaderCandidates = []; }, p => { p.branchCoverage[0].bpCandidates = []; }, p => { p.branchCoverage[0].leaderCandidates.pop(); p.branchCoverage[0].routingStatus = 'ROLE_EVIDENCE_PREPARED'; }, p => { p.branchCoverage.pop(); }, p => { p.branchCoverage[0].region = '错误区域'; }, p => { delete p.branchCoverage; }, p => { p.branchCoverage[0].canApprove = true; }, p => { p.branchCoverage[0].defaultHandlerReference = 'teacher-row-5'; }, p => { p.branchCoverage[0].leaderCandidates[0].reference = 'teacher-row-3'; }, p => { p.branchCoverage[0].leaderCandidates.push(p.branchCoverage[0].leaderCandidates[0]); }, p => { p.branchCoverage[0].combinedCandidates = []; }, p => { p.branchCoverage[0].leaderCandidates[0].roleKinds = ['BP']; }, p => { p.branchCoverage[0].leaderCandidates[0].accountId = 7; }, p => { p.branchCoverage[0].leaderCandidates[0].accountState = 'RECEIVED_ENABLED'; }, p => { p.branchCoverage[0].routingStatus = 'ROLE_EVIDENCE_PREPARED'; }];
  for (const change of invalid) { const p = fixture(); change(p); const bad = await mount({ preview: p }); await bad.switch('branches'); check(bad.text().includes('暂不可核实') && !bad.text().includes('合成人员丙'), 'Untrusted or absent coverage fails closed'); await bad.switch('people'); check(bad.node('table').textContent.includes('合成人员丙'), 'Coverage extension does not break original import personnel'); }
  for (const change of [p => { p.branchCoverage[0].leaderCandidates[0].accountId++; }, p => { p.rows[2].accountId++; }, p => { p.rows[2].personCode = 'WRONG'; }, p => { p.rows[2].alreadyImported = false; }]) { const p = fixture(true); change(p); const bad = await mount({ preview: p }); await bad.switch('branches'); check(bad.text().includes('暂不可核实'), 'Receipt, row and coverage account mappings must agree'); }
  for (const status of [401, 403, 409]) { let fail = false; const denied = await mount({ fetchOverride: (url, opts, normal, respond) => fail && url.endsWith('/account-import/preview') ? respond(null, status) : normal() }); await denied.switch('branches'); fail = true; await denied.importer().refresh(); check(!(denied.importRoot()?.textContent || '').includes('合成人员丙'), status + ': refresh clears stale coverage and personnel'); }
  const gate = deferred(); let delay = false; const late = await mount({ fetchOverride: (url, opts, normal, respond) => delay && url.endsWith('/account-import/preview') ? gate.promise.then(respond) : normal() }); await late.switch('branches'); delay = true; const refreshing = late.importer().refresh(); await tick(); check(!late.importRoot().textContent.includes('合成人员丙'), 'Pending refresh immediately clears coverage'); late.sandbox.beginRouteEpoch(); gate.resolve(fixture()); await refreshing; check(!late.importRoot().textContent, 'Route cleanup prevents late coverage resurrection'); check(late.previewRequests().at(-1).opts.signal.aborted, 'Route abort reaches the actual request');
  for (const role of ['viewer', 'manager']) { const nonadmin = support.harness({ role, preview: fixture() }); await nonadmin.mount(); equal(nonadmin.previewRequests().length, 0, role + ': no branch coverage HTTP'); }
  console.log(JSON.stringify({ ok: true, suite: 'original users branch coverage', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
